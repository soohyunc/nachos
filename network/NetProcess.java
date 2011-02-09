package nachos.network;

import nachos.machine.*;
import nachos.vm.*;

/**
 * A <tt>VMProcess</tt> that supports networking syscalls.
 */
public class NetProcess extends VMProcess {
	/**
	 * Allocate a new process.
	 */
	public NetProcess() {
		super();
	}

	private static final int syscallConnect = 11, syscallAccept = 12;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>11</td>
	 * <td><tt>int  connect(int host, int port);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>12</td>
	 * <td><tt>int  accept(int port);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall
	 *            the syscall number.
	 * @param a0
	 *            the first syscall argument.
	 * @param a1
	 *            the second syscall argument.
	 * @param a2
	 *            the third syscall argument.
	 * @param a3
	 *            the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallAccept:
			return handleAccept(a0);
		case syscallConnect:
			return handleConnect(a0,a1);
		default:
			return super.handleSyscall(syscall, a0, a1, a2, a3);
		}
	}

	/**
	 * The syscall handler for the connect syscall.
	 * @param host
	 * @param port
	 */
	private int handleConnect(int host, int port) {
		Lib.assertTrue(port >= 0 && port < Packet.linkAddressLimit);
		int fileDesc = getFileDescriptor();
		if (fileDesc != -1) {
			try {
				fileTable[fileDesc] = new OpenSocket(((NetKernel) kernel).postOffice.connect(host,port));
				FileRef.referenceFile(fileTable[fileDesc].getName());
			} catch (ClassCastException cce) {
				Lib.assertNotReached("Error - kernel not of type NetKernel");
			}
		}

		return fileDesc;
	}

	/**
	 * The syscall handler for the accept syscall.
	 * @param port
	 */
	private int handleAccept(int port) {
		Lib.assertTrue(port >= 0 && port < Packet.linkAddressLimit);
		int fileDesc = getFileDescriptor();
		if (fileDesc != -1) {
			Connection c = null;
			try {
				// Try to get an entry in the file table
				c = ((NetKernel) kernel).postOffice.accept(port);
			} catch (ClassCastException cce) {
				Lib.assertNotReached("Error - kernel not of type NetKernel");
			}

			if (c != null) {
				fileTable[fileDesc] = new OpenSocket(c);
				FileRef.referenceFile(fileTable[fileDesc].getName());
				return fileDesc;
			}
		}

		return -1;
	}

	/**
	 * A class to represent sockets that extends <tt>OpenFile</tt> so it can 
	 * reside in the process's page table.
	 */
	private static class OpenSocket extends OpenFile {
		OpenSocket(Connection c) {
			super(null, c.srcPort + "," + c.destAddress + "," + c.destPort);
			connection = c;
		}

		/**
		 * Close this socket and release any associated system resources.
		 */
		@Override
		public void close() {
			connection.close();
			connection = null;
		}

		@Override
		public int read(byte[] buf, int offset, int length) {
			Lib.assertTrue(offset < buf.length && length <= buf.length - offset);
			if (connection == null)
				return -1;
			else {
				byte[] receivedData = connection.receive(length);
				if (receivedData == null)
					return -1;
				else {
					System.arraycopy(receivedData, 0, buf, offset, receivedData.length);
					return receivedData.length;
				}
			}
		}

		@Override
		public int write(byte[] buf, int offset, int length) {
			if (connection == null)
				return -1;
			else
				return connection.send(buf, offset, length);
		}

		/** The underlying connection for this socket. */
		private Connection connection;
	}
}
