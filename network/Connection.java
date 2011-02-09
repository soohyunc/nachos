package nachos.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import nachos.machine.Kernel;
import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.MalformedPacketException;
import nachos.machine.Packet;
import nachos.threads.Condition;
import nachos.threads.Lock;

class Connection {
	
	private Lock stateLock = new Lock();
	private Condition connectionEstablished;
	private NTPState currentState = NTPState.CLOSED;
	private boolean calledClose = false;

	private SendWindow sendWindow = new SendWindow();
	private ReceiveWindow receiveWindow = new ReceiveWindow();
	private ByteStream residualData = new ByteStream();
	private ByteStream sendBuffer = new ByteStream();
	
	int destAddress, destPort, srcPort;
	
	Connection(int _destAddress, int _destPort, int _srcPort) {
		destAddress = _destAddress;
		destPort = _destPort;
		srcPort = _srcPort;
		connectionEstablished = new Condition(stateLock);
	}
	
	/**
	 * Connect to another nachos instance
	 * <p>
	 * This will return false if it receives a SYN packet for this connection. This signals 
	 * a potential protocol deadlock which we handle by trying a different local port.
	 * @return true if the connection was successful
	 */
	boolean connect() {
		stateLock.acquire();
		currentState.connect(this);
		stateLock.release();
		
		//return false if we hit the deadlock case
		if (currentState == NTPState.DEADLOCK) {
			currentState = NTPState.CLOSED;
			return false;
		}
		return true;
	}
	
	/**
	 * Establish that the connection has been accepted by the local instance of 
	 * NachOS.
	 */
	boolean accept() {
		stateLock.acquire();
		currentState.accept(this);
		stateLock.release();
		return currentState == NTPState.ESTABLISHED;
	}

	/**
	 * Begin to shutdown the connection
	 */
	void close() {
		stateLock.acquire();
		calledClose = true;
		currentState.close(this);
		stateLock.release();
	}
	
	/** Called when we transition to the CLOSED state */
	protected void finished() {
		// Sanity check
		if (calledClose || exhausted()) {
			sendWindow.clear();
			receiveWindow.clear();
			((NetKernel) Kernel.kernel).postOffice.finished(this);
		}
	}

	/**
	 * Called by PostOffice when a message is received for this Connection
	 */
	void packet(MailMessage msg) {
		stateLock.acquire();
		switch (msg.flags) {
		case MailMessage.SYN:
			Lib.debug(networkDebugFlag,"Receiving SYN");
			currentState.syn(this, msg);
			break;
		case MailMessage.SYN | MailMessage.ACK:
			Lib.debug(networkDebugFlag,"Receiving SYNACK");
			currentState.synack(this, msg);
			break;
		case MailMessage.DATA:
			Lib.debug(networkDebugFlag,"Receiving DATA: " + msg.sequence + " with content length " + msg.contents.length);
			currentState.data(this, msg);
			break;
		case MailMessage.ACK:
			Lib.debug(networkDebugFlag,"Receiving ack for " + msg.sequence);
			currentState.ack(this, msg);
			break;
		case MailMessage.STP:
			Lib.debug(networkDebugFlag,"Receiving STP with " + msg.sequence);
			receiveWindow.stopAt(msg.sequence);
			currentState.stp(this, msg);
			break;
		case MailMessage.FIN:
			Lib.debug(networkDebugFlag,"Receivin FIN");
			currentState.fin(this, msg);
			break;
		case MailMessage.FIN | MailMessage.ACK:
			Lib.debug(networkDebugFlag,"Receivin FINACK");
			currentState.finack(this, msg);
			break;
		default:
			// Drop invalid packet
			Lib.debug(networkDebugFlag,"OMG INVALID PACKET");
			break;
		}
		stateLock.release();
	}
	
	/** 
	 * Called by PostOffice when the retransmission timer expires
	 */
	void retransmit() {
		stateLock.acquire();
		currentState.timer(this);
		stateLock.release();
	}
	
	/**
	 * Queue up data in buffer for sending 
	 */
	int send(byte[] buffer, int offset, int length) {
		byte[] toSend = new byte[length];
		if (length > buffer.length - offset)
			System.arraycopy(buffer, offset, toSend, 0, buffer.length - offset);
		else 
			System.arraycopy(buffer, offset, toSend, 0, length);
		
		stateLock.acquire();
		int sent = currentState.send(this, toSend);
		stateLock.release();
		return sent;
	}
	
	/**
	 * Read data from the connection
	 * @param bytes 
	 * 	max number of bytes to read
	 * @return
	 * 	buffer with data received
	 */
	byte[] receive(int bytes) {
		stateLock.acquire();
		byte[] data = currentState.recv(this, bytes);
		stateLock.release();
		return data;
	}

	/** Send an empty/flagged packet NAOW */
	private void transmit(int flags) {
		switch (flags) {
		case MailMessage.SYN:
			Lib.debug(networkDebugFlag,"Sending SYN");
			break;
		case MailMessage.SYN | MailMessage.ACK:
			Lib.debug(networkDebugFlag,"Sending SYNACK");
			break;
		case MailMessage.FIN:
			Lib.debug(networkDebugFlag,"Sending FIN");
			break;
		case MailMessage.FIN | MailMessage.ACK:
			Lib.debug(networkDebugFlag,"Sending FINACK");
			break;
		default:
			Lib.debug(networkDebugFlag,"Sending [" + flags + "]");//This should never happen
		}
		((NetKernel) Kernel.kernel).postOffice.enqueue(makeMessage(flags,0,MailMessage.EMPTY_CONTENT).packet);
	}
	/** Ack a packet */
	private void transmitAck(int sequence) {
		Lib.debug(networkDebugFlag,"Sending ACK for " + sequence);
		((NetKernel) Kernel.kernel).postOffice.enqueue(makeMessage(MailMessage.ACK, sequence, MailMessage.EMPTY_CONTENT).packet);
	}
	private void transmitStp() {
		((NetKernel) Kernel.kernel).postOffice.enqueue(sendWindow.stopPacket(sendBuffer));
	}
	/** Flood the network with data! */
	private void transmitData() {
		while (sendBuffer.size() > 0 && !sendWindow.full()) {
			byte[] toSend = sendBuffer.dequeue(Math.min(MailMessage.maxContentsLength, sendBuffer.size()));
			MailMessage msg = sendWindow.add(toSend);
			if (msg != null) {
				Lib.debug(networkDebugFlag,"Sending DATA with sequence " + msg.sequence + " and length " + msg.contents.length);
				((NetKernel) Kernel.kernel).postOffice.enqueue(msg.packet);
			}
			else {
				// We should never get here
				Lib.assertNotReached("Attempted to add packet to full send window");
				break;
			}
		}
	}
	
	/**
	 * Construct a new packet addressed to the other end point of this Connection
	 */
	private MailMessage makeMessage(int flags, int sequence, byte[] contents) {
		try {
			return new MailMessage(destAddress, destPort, Machine.networkLink().getLinkAddress(), srcPort, flags, sequence, contents);
		} catch (MalformedPacketException e) {
			return null;
		}
	}
	
	private boolean exhausted() {
		return residualData.size() == 0 && receiveWindow.empty();
	}

	private enum NTPState {		
		CLOSED {
			@Override
			void connect(Connection c) {
				// Establish 
				c.transmit(MailMessage.SYN);
				// Immediately transition
				Lib.debug(networkDebugFlag,"Transition to SYN_SENT");
				c.currentState = SYN_SENT;
				// Sleep until connection established
				c.connectionEstablished.sleep();
			}
			
			@Override
			byte[] recv(Connection c, int maxBytes) {
				byte[] data = super.recv(c, maxBytes);
				
				// Exhausted?
				if (c.exhausted())
					c.finished();
				
				return (data.length == 0) ? (null) : (data);
			}
			
			@Override
			int send(Connection c, byte[] buffer) {
				return -1;
			}
			
			@Override
			void syn(Connection c, MailMessage msg) {
				// Transition to SYN_RCVD
				Lib.debug(networkDebugFlag,"Tranition to SYN_RCVD");
				c.currentState = SYN_RCVD;
			} 
			
			@Override
			void fin(Connection c, MailMessage msg) {
				// Send FINACK
				c.transmit(MailMessage.FIN | MailMessage.ACK);
			}

		},
		
		SYN_SENT {
			@Override
			void timer(Connection c) {
				// Send SYN
				c.transmit(MailMessage.SYN);
			}

			@Override
			void syn(Connection c, MailMessage msg) {
				// Protocol deadlock!
				Lib.debug(networkDebugFlag,"Transition to DEADLOCK");
				c.currentState = DEADLOCK;
				c.connectionEstablished.wake();
			} 
			
			@Override
			void synack(Connection c, MailMessage msg) {
				// Goto ESTABLISHED, wake thread waiting in connect()
				Lib.debug(networkDebugFlag,"Transition to ESTABLISHED");
				c.currentState = ESTABLISHED;
				c.connectionEstablished.wake();
			}
			
			@Override
			void data(Connection c, MailMessage msg) {
				// Send SYN
				c.transmit(MailMessage.SYN);
			}
			@Override
			void stp(Connection c, MailMessage msg) {
				// Send SYN
				c.transmit(MailMessage.SYN);
			}
			@Override
			void fin(Connection c, MailMessage msg) {
				// Send SYN
				c.transmit(MailMessage.SYN);
			}
		},
		
		SYN_RCVD {
			@Override
			void accept(Connection c) {
				// Send SYNACK, goto ESTABLISHED
				c.transmit(MailMessage.SYN | MailMessage.ACK);
				Lib.debug(networkDebugFlag,"Transition to ESTABLISHED");
				c.currentState = ESTABLISHED;
			}
		},
		
		ESTABLISHED {			
			@Override
			void close(Connection c) {
				if (c.sendWindow.empty() && c.sendBuffer.size() == 0) {//No more data to send, either in queue or window
					c.transmit(MailMessage.FIN);
					Lib.debug(networkDebugFlag,"Transition to CLOSING");
					c.currentState = CLOSING;
				}
				else {
					c.transmitStp();
					Lib.debug(networkDebugFlag,"Transition to STP_SENT");
					c.currentState = STP_SENT;
				}
			}
			
			@Override
			void syn(Connection c, MailMessage msg) {
				// Send SYNACK
				c.transmit(MailMessage.SYN | MailMessage.ACK);
			}
			
			@Override
			void data(Connection c, MailMessage msg) {
				if (c.receiveWindow.add(msg))
					c.transmitAck(msg.sequence);
				else
					Lib.debug(networkDebugFlag,"Dropped DATA packet " + msg.sequence);
			}
			
			@Override
			void ack(Connection c, MailMessage msg) {
				c.sendWindow.acked(msg.sequence);
				c.transmitData();
			}
			
			@Override
			void stp(Connection c, MailMessage msg) {
				c.sendWindow.clear();
				Lib.debug(networkDebugFlag,"Transition to STP_RCVD");
				c.currentState = STP_RCVD;
			}
			
			@Override
			void fin(Connection c, MailMessage msg) {
				c.sendWindow.clear();
				c.transmit(MailMessage.FIN | MailMessage.ACK);
				Lib.debug(networkDebugFlag,"Transition to CLOSED");
				c.currentState = CLOSED;
				c.finished();
			}
		},
		
		STP_SENT {
			@Override int send(Connection c, byte[] buffer) {
				// Can't send more data on a closing connection
				return -1;
			}
			
			@Override
			void timer(Connection c) {
				if (c.sendWindow.empty())
					c.transmit(MailMessage.FIN);
				else
					c.transmitStp();
				
				// Retransmit unacknowledged
				super.timer(c);
			}
			
			@Override
			void ack(Connection c, MailMessage msg) {
				c.sendWindow.acked(msg.sequence);
				c.transmitData();
				
				if (c.sendWindow.empty() && c.sendBuffer.size() == 0) {
					c.transmit(MailMessage.FIN);
					c.currentState = CLOSING;
				}
			}
			
			@Override
			void syn(Connection c, MailMessage msg) {
				c.transmit(MailMessage.SYN | MailMessage.ACK);
			}
			
			@Override
			void data(Connection c, MailMessage msg) {
				c.transmitStp();
			}
			
			@Override
			void stp(Connection c, MailMessage msg) {
				c.sendWindow.clear();
				c.transmit(MailMessage.FIN);
				c.currentState = CLOSING;
			}
			
			@Override
			void fin(Connection c, MailMessage msg) {
				c.transmit(MailMessage.FIN | MailMessage.ACK);
				c.currentState = CLOSED;
				c.finished();
			}
			
		},
		
		STP_RCVD {
			@Override
			int send(Connection c, byte[] buffer) {
				// Can't send more data on a closing connection
				return -1;
			}
			
			@Override
			void close(Connection c) {
				c.transmit(MailMessage.FIN);
				Lib.debug(networkDebugFlag,"Transition to CLOSING");
				c.currentState = CLOSING;
			}
			
			@Override
			void data(Connection c, MailMessage msg) {
				if (c.receiveWindow.add(msg))
					c.transmitAck(msg.sequence);
				else
					Lib.debug(networkDebugFlag,"Dropped DATA packet " + msg.sequence);
			}
			
			@Override
			void fin(Connection c, MailMessage msg) {
				c.transmit(MailMessage.FIN | MailMessage.ACK);
				Lib.debug(networkDebugFlag,"Transition to CLOSED");
				c.currentState = CLOSED;
				c.finished();
			}
		},
		CLOSING {
			@Override
			int send(Connection c, byte[] buffer) {
				// Can't send more data on a closing connection
				return -1;
			}
			
			@Override
			void timer(Connection c) {
				c.transmit(MailMessage.FIN);
			}
			
			@Override
			void syn(Connection c, MailMessage msg) {
				c.transmit(MailMessage.SYN | MailMessage.ACK);
			}
			
			@Override
			void data(Connection c, MailMessage msg) {
				c.transmit(MailMessage.FIN);
			}
			
			@Override
			void stp(Connection c, MailMessage msg) {
				c.transmit(MailMessage.FIN);
			}
			
			@Override
			void fin(Connection c, MailMessage msg) {
				c.transmit(MailMessage.FIN | MailMessage.ACK);
				Lib.debug(networkDebugFlag,"Transition to CLOSED");
				c.currentState = CLOSED;
				c.finished();
			}

			@Override
			void finack(Connection c, MailMessage msg) {
				Lib.debug(networkDebugFlag,"Transition to CLOSED");
				c.currentState = CLOSED;
				c.finished();
			}
		},
		
		DEADLOCK {};

		/** an app called connect() */
		void connect(Connection c) {}
		/** an app called accept() */
		void accept(Connection c) {}
		/** an app called read() */
		byte[] recv(Connection c, int maxBytes) {
			while (c.residualData.size() < maxBytes) {
				MailMessage msg = c.receiveWindow.remove();
				if (msg == null)
					break;
				try {
					c.residualData.write(msg.contents);
				} catch (IOException e) {}
			}
			
			return c.residualData.dequeue(Math.min(c.residualData.size(), maxBytes));
		}
		/** an app called write(). */
		int send(Connection c, byte[] buffer) {
			try {
				c.sendBuffer.write(buffer);
			} catch (IOException e) {}
			c.transmitData();
			return buffer.length;
		} 
		/** an app called close(). */
		void close(Connection c) {}
		/** the retransmission timer ticked. */
		void timer(Connection c) {
			Lib.debug(networkDebugFlag,"Retransmitting unacknowledged packets");
			((NetKernel) Kernel.kernel).postOffice.enqueue(c.sendWindow.packets());
		}
		/** a SYN packet is received (a packet with the SYN bit set). */
		void syn(Connection c, MailMessage msg) {} 
		/** a SYN/ACK packet is received (a packet with the SYN and ACK bits set). */
		void synack(Connection c, MailMessage msg) {}
		/** a data packet is received (a packet with none of the SYN, ACK, STP, or FIN bits set). */
		void data(Connection c, MailMessage msg) {}
		/** an ACK packet is received (a packet with the ACK bit set). */
		void ack(Connection c, MailMessage msg) {}
		/** a STP packet is received (a packet with the STP bit set). */
		void stp(Connection c, MailMessage msg) {}
		/** a FIN packet is received (a packet with the FIN bit set). */
		void fin(Connection c, MailMessage msg) {}
		/** a FIN/ACK packet is received (a packet with the FIN and ACK bits set). */
		void finack(Connection c, MailMessage msg) {}
	}
	
	private static class Window {
		protected static final int WINDOW_SIZE = 16;
		protected ArrayList<MailMessage> window = new ArrayList<MailMessage>(WINDOW_SIZE);
		protected int startSequence, lastSequenceNumber = -1;
		Window() {
			clear();
		}
		
		boolean add(MailMessage msg) {
			// Message was previously seen and dequeued already
			if (msg.sequence < startSequence)
				return true;
			
			// Make sure message fits in window
			if (msg.sequence >= startSequence + WINDOW_SIZE)
				return false;
			// Make sure message doesn't go past STOP
			if (lastSequenceNumber > -1 && msg.sequence >= lastSequenceNumber)
				return false;
			
			int windowIndex = msg.sequence - startSequence;
			while (window.size() < windowIndex+1)	// Expand window buffer if necessary
				window.add(null);
			if (window.get(windowIndex) == null)	// Only add packet to window if we haven't seen it before
				window.set(windowIndex, msg);
			
			return true;
		} 
		
		boolean empty() {
			return window.size() == 0;
		}
		boolean full() {
			return window.size() == WINDOW_SIZE;
		}
		
		List<Packet> packets() {
			List<Packet> lst = new ArrayList<Packet>();
			for (MailMessage m : window) {
				if (m != null)
					lst.add(m.packet);
			}
			Lib.debug(networkDebugFlag,"  Window has " + lst.size() + " packets");
			return lst;
		}
		
		void clear() {
			window.clear();
			startSequence = 0;
			lastSequenceNumber = -1;
		}
	}
	
	private class SendWindow extends Window {
		protected int sequenceNumber;	// Sequence number to assign next outgoing packet

		void acked(int sequence) {
			if (sequence < startSequence || sequence >= startSequence + window.size() || sequence >= lastSequenceNumber)
				return;
			
			int windowIndex = sequence - startSequence;
			window.set(windowIndex, null);
			
			// Since this is the send buffer, we assume that we haven't packed any gaps into it (because add(byte[]) won't)
			while (window.size() > 0 && window.get(0) == null) {
				window.remove(0);
				startSequence++;
			}
		}

		MailMessage add(byte[] bytes) {
			MailMessage msg = makeMessage(MailMessage.DATA, sequenceNumber, bytes);
			if (super.add(msg)) {
				// Message added, increment sequence counter
				sequenceNumber++;
			} else {
				// Couldn't add to window
				msg = null;
			}
			
			return msg;
		}

		Packet stopPacket(ByteStream sendBuffer) {
			if (stopPacket == null) {
				lastSequenceNumber = (startSequence + window.size()) + (sendBuffer.size() / MailMessage.maxContentsLength) + (sendBuffer.size() % MailMessage.maxContentsLength != 0 ? 1 : 0);
				stopPacket = makeMessage(MailMessage.STP, lastSequenceNumber, MailMessage.EMPTY_CONTENT).packet;
			}
			
			return stopPacket;
		}
		
		private Packet stopPacket = null;
	}
	
	private static class ReceiveWindow extends Window {
		MailMessage remove() {
			if (window.size() > 0 && window.get(0) != null) {
				startSequence++;
				return window.remove(0);
			}
			else
				return null;
		}

		void stopAt(int sequence) {
			if (lastSequenceNumber == -1)
				lastSequenceNumber = sequence;
		}
	}
	
	private static final char networkDebugFlag = 'n';

	private static class ByteStream extends ByteArrayOutputStream {
		byte[] dequeue(int bytes) {
			byte[] temp = super.toByteArray(), returnArray;
			
			if (bytes > temp.length)
				returnArray = new byte[temp.length];
			else
				returnArray = new byte[bytes];
			
			System.arraycopy(temp, 0, returnArray, 0, returnArray.length);
			
			super.reset();
			
			super.write(temp, returnArray.length, temp.length - returnArray.length);
			
			return returnArray;
		}
	}
}
