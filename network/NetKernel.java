package nachos.network;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import nachos.machine.*;
import nachos.threads.*;
import nachos.vm.*;

/**
 * A kernel with network support.
 */
public class NetKernel extends VMKernel {
	/**
	 * Allocate a new networking kernel.
	 */
	public NetKernel() {
		super();
	}

	@Override
	protected OpenFile openSwapFile() {
		return fileSystem.open("swapfile" + Machine.networkLink().getLinkAddress(), true);
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		postOffice = new SocketPostOffice();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		postOffice.shutdown();
		super.terminate();
	}

	SocketPostOffice postOffice;

	/**
	 * A class to encapsulate the management of the NachOS Transport Protocol with threads to do delivery and such.
	 */
	static class SocketPostOffice {
		SocketPostOffice() {
			nothingToSend = new Condition(sendLock);
			terminationCondition = new Condition(terminationLock);

			//Set up the delivery thread interrupt handlers
			Machine.networkLink().setInterruptHandlers(new Runnable() {
				public void run() {
					receiveInterrupt();
				}
			},
			new Runnable() {
				public void run() {
					sendInterrupt();
				}
			});

			//Set up the postal worker threads
			KThread postalDeliveryThread = new KThread(
					new Runnable() {
						public void run() {
							postalDelivery();
						}
					}
			), postalSendThread = new KThread(
					new Runnable() {
						public void run() {
							send();
						}
					}
			), timerInterruptThread = new KThread(
					new Runnable() {
						public void run() {
							timerRoutine();
						}
					}
			);

			postalDeliveryThread.fork();
			postalSendThread.fork();
			timerInterruptThread.fork();
		}

		Connection accept(int port) {
			Connection c = awaitingConnectionMap.retrieve(port);

			if (c != null)
				c.accept();

			return c;
		}

		Connection connect(int host, int port) {
			Connection connection = null;
			boolean found = false;
			int srcPort, tries = 0;
			while (connection == null) {
				//Find a source port for the connection
				srcPort = portGenerator.nextInt(MailMessage.portLimit);
				tries = 0;
				
				while (!(found = (connectionMap.get(srcPort, host, port) == null)) && tries++ < MailMessage.portLimit)
					srcPort = (srcPort+1) % MailMessage.portLimit;
				
				if (found) {
					connection = new Connection(host, port, srcPort);
					connectionMap.put(connection);
					if (!connection.connect()) {
						connectionMap.remove(connection);
						connection = null;
					}
				}//else port saturation, so randomize and try again
			}

			return connection;
		}

		private Random portGenerator = new Random();

		/**
		 * Closes the connection remove it from the connectionMap, if it exists.
		 * <p>
		 * This should only be called from the kernel when closing a "live" connection 
		 * (i.e. one that successfully returned from connect).
		 * @param connection (not null)
		 */
		void close(Connection connection) {
			if (connectionMap.remove(connection.srcPort, connection.destAddress, connection.destPort) != null)
				connection.close();
		}

		/**
		 * Closes all the <tt>Connection</tt> instances in both the <tt>connectionMap</tt> 
		 * and <tt>awaitingConnectionMap</tt>, and remove the instances from the respective maps.
		 */
		void shutdown() {
			connectionMap.shutdown();
			awaitingConnectionMap.shutdown();

			terminationLock.acquire();

			while (!connectionMap.isEmpty())
				terminationCondition.sleep();

			terminationLock.release();

		}

		private Lock terminationLock = new Lock();
		private Condition terminationCondition;

		/**
		 * Called by a <tt>Connection</tt> instance when it is fully closed and 
		 * exhausted. This causes NetKernel to remove it from its connection mappings.
		 * @param connection
		 */
		void finished(Connection c) {
			if (connectionMap.remove(c.srcPort, c.destAddress, c.destPort) != null) {
				terminationLock.acquire();
				terminationCondition.wake();
				terminationLock.release();
			}
		}

		/**
		 * Enqueue a packet to be sent over the network.
		 * @param p
		 */
		void enqueue(Packet p) {
			sendLock.acquire();
			sendQueue.add(p);
			nothingToSend.wake();
			sendLock.release();
		}

		/**
		 * Enqueue an ordered sequence of packets, using a List.
		 * 
		 * We can switch to an array if that is more convenient.
		 */
		void enqueue(List<Packet> ps) {
			sendLock.acquire();
			sendQueue.addAll(ps);
			nothingToSend.wake();
			sendLock.release();
		}

		/**
		 * The method for delivering the packets to the appropriate Sockets.
		 */
		private void postalDelivery() {
			MailMessage pktMsg = null;
			Connection connection = null;
			while (true) {
				messageReceived.P();

				try {
					pktMsg = new MailMessage(Machine.networkLink().receive());
				} catch (MalformedPacketException e) {
					continue;//Just drop the packet
				}

				if ((connection = connectionMap.get(pktMsg.dstPort, pktMsg.packet.srcLink, pktMsg.srcPort)) != null)
					connection.packet(pktMsg);
				else if (pktMsg.flags == MailMessage.SYN) {
					connection = new Connection(pktMsg.packet.srcLink, pktMsg.srcPort, pktMsg.dstPort);
					connection.packet(pktMsg);

					//Put it in the connectionMap
					connectionMap.put(connection);

					//Put it in the awaiting connection map
					awaitingConnectionMap.addWaiting(connection);
				} else if (pktMsg.flags == MailMessage.FIN) {
					try {
						enqueue(new MailMessage(pktMsg.packet.srcLink, pktMsg.srcPort, pktMsg.packet.dstLink, pktMsg.dstPort, MailMessage.FIN | MailMessage.ACK, 0, MailMessage.EMPTY_CONTENT).packet);
					} catch (MalformedPacketException e) {
					}
				}
			}
		}

		/**
		 * Called when a packet has arrived and can be dequeued from the network
		 * link.
		 */
		private void receiveInterrupt() {
			messageReceived.V();
		}

		/**
		 * The method for sending packets over the network link, from a queue.
		 */
		private void send() {
			Packet p = null;
			while (true) {
				sendLock.acquire();

				//MESA Style waiting
				while (sendQueue.isEmpty())
					nothingToSend.sleep();

				//Dequeue the packet
				p = sendQueue.poll();
				sendLock.release();

				//Now work on sending the packet
				Machine.networkLink().send(p);
				messageSent.P();
			}
		}

		/**
		 * Called when a packet has been sent and another can be queued to the
		 * network link. Note that this is called even if the previous packet was
		 * dropped.
		 */
		private void sendInterrupt() {
			messageSent.V();
		}

		/**
		 * The routine for the interrupt handler.
		 * 
		 * Fires off an event to call the retransmit method on all sockets that require a timer 
		 * interrupt.
		 */
		private void timerRoutine() {
			while (true) {
				alarm.waitUntil(20000);

				//Call the retransmit method on all the Connections
				connectionMap.retransmitAll();
				awaitingConnectionMap.retransmitAll();//FIXME: This may not be necessary
			}
		}

		private ConnectionMap connectionMap = new ConnectionMap();
		private AwaitingConnectionMap awaitingConnectionMap = new AwaitingConnectionMap();

		private Semaphore messageReceived = new Semaphore(0);
		private Semaphore messageSent = new Semaphore(0);
		private Lock sendLock = new Lock();

		/** A condition variable to wait on in case there is nothing to send. */
		private Condition nothingToSend;

		private LinkedList<Packet> sendQueue = new LinkedList<Packet>();
	}

	/**
	 * A multimap to handle hash collisions
	 */
	private static class ConnectionMap {
		void retransmitAll() {
			lock.acquire();
			for (Connection c : map.values())
				c.retransmit();
			lock.release();
		}

		Connection remove(Connection conn) {
			return remove(conn.srcPort, conn.destAddress, conn.destPort);
		}

		boolean isEmpty() {
			lock.acquire();
			boolean b = map.isEmpty();
			lock.release();
			return b;
		}

		/**
		 * Closes all connections and removes them from this map.
		 */
		void shutdown() {
			lock.acquire();
			for (Connection c : map.values())
				c.close();
			lock.release();
		}

		Connection get(int sourcePort, int destinationAddress, int destinationPort) {
			lock.acquire();
			Connection c = map.get(new SocketKey(sourcePort,destinationAddress,destinationPort));
			lock.release();
			return c;
		}

		void put(Connection c) {
			lock.acquire();
			map.put(new SocketKey(c.srcPort,c.destAddress,c.destPort),c);
			lock.release();
		}

		Connection remove(int sourcePort, int destinationAddress, int destinationPort) {
			lock.acquire();
			Connection c = map.remove(new SocketKey(sourcePort,destinationAddress,destinationPort));
			lock.release();
			return c;
		}

		private HashMap<SocketKey, Connection> map = new HashMap<SocketKey, Connection>();
		
		private Lock lock = new Lock();
	}

	/**
	 * A class that holds <tt>Connection</tt>s waiting to be accepted. 
	 */
	private static class AwaitingConnectionMap {
		/**
		 * Add the connection to the set of waiting connections
		 * @param c
		 * @return true if the connection didn't already exist
		 */
		boolean addWaiting(Connection c) {
			boolean returnBool = false;
			lock.acquire();
			if (!map.containsKey(c.srcPort))
				map.put(c.srcPort, new HashMap<SocketKey,Connection>());

			if (map.get(c.srcPort).containsKey(null))
				returnBool = false;//Connection already exists
			else {
				map.get(c.srcPort).put(new SocketKey(c.srcPort,c.destAddress,c.destPort), c);
				returnBool = true;
			}
			lock.release();
			return returnBool;
		}

		/**
		 * Closes all connections and removes them from this map.
		 */
		void shutdown() {
			lock.acquire();
			map.clear();
			lock.release();
		}

		void retransmitAll() {
			lock.acquire();
			for (HashMap<SocketKey,Connection> hm : map.values())
				for (Connection c : hm.values())
					c.retransmit();
			lock.release();
		}

		/**
		 * Retrieve a <tt>Connection</tt> from the given port and remove it from <tt>this</tt>. Return null if one doesn't exist.
		 * @param port
		 * @return a connection on the port if it exists.
		 */
		Connection retrieve(int port) {
			Connection c = null;
			lock.acquire();
			if (map.containsKey(port)) {
				HashMap<SocketKey,Connection> mp = map.get(port);

				c = mp.remove(mp.keySet().iterator().next());

				//Get rid of set if it is empty
				if (mp.isEmpty())
					map.remove(port);
			}
			lock.release();
			
			return c;
		}

		private HashMap<Integer,HashMap<SocketKey,Connection>> map = new HashMap<Integer,HashMap<SocketKey,Connection>>();
		
		private Lock lock = new Lock();
	}

	private static class SocketKey {
		SocketKey(int srcPrt, int destAddr, int destPrt) {
			sourcePort = srcPrt;
			destAddress = destAddr;
			destPort = destPrt;
			hashcode = Long.valueOf(((long) sourcePort) + ((long) destAddress) + ((long) destPort)).hashCode();
		}

		@Override
		public int hashCode() {
			return hashcode;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			else if (o instanceof SocketKey) {
				SocketKey oC = (SocketKey) o;
				return sourcePort == oC.sourcePort &&
				destAddress == oC.destAddress &&
				destPort == oC.destPort;
			} else
				return false;
		}

		private int sourcePort, destAddress, destPort, hashcode;
	}
}
