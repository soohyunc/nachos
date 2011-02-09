package nachos.threads;

import java.util.LinkedList;
import nachos.machine.*;

/**
 * A synchronized queue.
 */
public class SynchList<T> {
	/**
	 * Allocate a new synchronized queue.
	 */
	public SynchList() {
		list = new LinkedList<T>();
		lock = new Lock();
		listEmpty = new Condition(lock);
	}

	/**
	 * Add the specified object to the end of the queue. If another thread is
	 * waiting in <tt>removeFirst()</tt>, it is woken up.
	 * 
	 * @param o
	 *            the object to add. Must not be <tt>null</tt>.
	 */
	public void add(T t) {
		Lib.assertTrue(t != null);

		lock.acquire();
		list.add(t);
		listEmpty.wake();
		lock.release();
	}

	/**
	 * Remove an object from the front of the queue, blocking until the queue is
	 * non-empty if necessary.
	 * 
	 * @return the element removed from the front of the queue.
	 */
	public T removeFirst() {
		T t;

		lock.acquire();
		while (list.isEmpty())
			listEmpty.sleep();
		t = list.removeFirst();
		lock.release();

		return t;
	}
	
	/**
	 * A method to check to see if the underlying list is empty
	 * @return True if the list is empty
	 */
	public boolean isEmpty() {
		boolean returnBool;
		lock.acquire();
		returnBool = list.isEmpty();
		lock.release();
		return returnBool;
	}

	private static class PingTest<T> implements Runnable {
		PingTest(SynchList<T> ping, SynchList<T> pong) {
			this.ping = ping;
			this.pong = pong;
		}

		public void run() {
			for (int i = 0; i < 10; i++)
				pong.add(ping.removeFirst());
		}

		private SynchList<T> ping;
		private SynchList<T> pong;
	}

	/**
	 * Test that this module is working.
	 */
	public static void selfTest() {
		SynchList<Integer> ping = new SynchList<Integer>();
		SynchList<Integer> pong = new SynchList<Integer>();

		new KThread(new PingTest<Integer>(ping, pong)).setName("ping").fork();

		for (int i = 0; i < 10; i++) {
			Integer o = new Integer(i);
			ping.add(o);
			Lib.assertTrue(pong.removeFirst() == o);
		}
	}

	private LinkedList<T> list;
	private Lock lock;
	private Condition listEmpty;
}
