package nachos.threads;

import nachos.machine.Lib;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	
	private Lock lock;
	private Condition speaker;
	private Condition listener;
	
	private int sharedWord = 0;
	private Boolean sharedWordInUse = false;
	private int waitingListeners = 0;
	
	/**
	 * Allocate a new communicator.
	 */
	public Communicator() {
		lock = new Lock();
		speaker = new Condition(lock);
		listener = new Condition(lock);
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word
	 *            the integer to transfer.
	 */
	public void speak(int word) {
		lock.acquire();
		// Wait until someone is listening, and the shared storage is free
		while (waitingListeners == 0 || sharedWordInUse)
			speaker.sleep();
		
		// Claim the shared storage
		sharedWordInUse = true;
		sharedWord = word;
		
		// Tell a listener that a value is ready to be picked up
		listener.wake();
		lock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		lock.acquire();
		// Tell a speaker, if one exists, that a listener is available
		waitingListeners++;
		speaker.wake();
		listener.sleep();
		
		// A speaker has notified us that there is data ready
		int word = sharedWord;
		sharedWordInUse = false;
		waitingListeners--;
		
		// Tell any waiting speakers that the shared storage is free
		speaker.wake();
		lock.release();
		
		return word;
	}
	
	
	private static final char dbgCommunicator = 'c'; 	// Flag to enable Communicator debug output

	/**
	 * Tests whether this module is working.
	 */
	public static void selfTest() {
		KThread speak, listen;
		
		Lib.debug(dbgCommunicator, "Communicator Self Test");

		// Test that a single word is passed successfully
		speak = new KThread(new TestSpeakerThread(23));
		listen = new KThread(new TestListenerThread());
		
		speak.fork(); listen.fork();
		speak.join(); listen.join();
		Lib.debug(dbgCommunicator, (TestChattyThread.getReceived() == 23 ? "[PASS]" : "[FAIL]") + ": Word transfered between threads successfully.");

		// Test that speaker blocks until listener available
		speak = new KThread(new TestSpeakerThread(0));
		listen = new KThread(new TestListenerThread());
		
		speak.fork(); listen.fork();
		speak.join(); listen.join();
		TestChattyThread.checkState(TestChattyThread.TESTSTATE.LISTENING, "Speaker blocks until listener called.");

		// Test that listener blocks until speaker available
		listen = new KThread(new TestListenerThread());
		speak = new KThread(new TestSpeakerThread(0));
		
		listen.fork(); speak.fork();
		listen.join(); speak.join();
		TestChattyThread.checkState(TestChattyThread.TESTSTATE.SPEAKING, "Listener blocks until speaker called.");
		
		// Test that two speakers don't overwrite the shared storage
		TestChattyThread.reset();
		new KThread(new TestSpeakerThread(3)).fork();
		new KThread(new TestSpeakerThread(7)).fork();

		new KThread(new TestListenerThread()).fork();
		listen = new KThread(new TestListenerThread());
		listen.fork(); listen.join();
		
		// TestChattyThread sums all the received words, so if any speaker overwrites the storage, then we won't get the correct result.
		Lib.debug(dbgCommunicator, (TestChattyThread.getReceived() == 10 ? "[PASS]" : "[FAIL]") + ": Multiple speakers don't overwrite shared storage.");

		// Stress test with several speakers and listeners to make sure everyone exits properly
		for (int i = 0; i < 10; i++) {
			new KThread(new TestListenerThread()).fork();
		}
		for (int i = 0; i < 10; i++) {
			speak = new KThread(new TestSpeakerThread(3));
			speak.fork(); speak.join();
		}
		
		Lib.debug(dbgCommunicator, (TestChattyThread.comm.waitingListeners == 0 ? "[PASS]" : "[FAIL]") + ": Stress test completed.");

	}
	
	/**
	 * General Communicator test class
	 * Allows subclasses to set the current state of a test and to update 
	 * a running tally of all received words, which are used by selfTest
	 * to verify that communication occurred properly.
	 */
	private abstract static class TestChattyThread implements Runnable {
		static Communicator comm = new Communicator();
		
		static Lock lock = new Lock();
		static enum TESTSTATE {NONE, SPEAKING, LISTENING}; 
		static TESTSTATE state = TESTSTATE.NONE;
		
		static int received = 0;

		public static void reset() {
			lock.acquire();
			state = TESTSTATE.NONE;
			received = 0;
			lock.release();
		}
		static void setState(TESTSTATE s) {
			lock.acquire();
			state = s;
			lock.release();
		}
		public static void checkState(TESTSTATE s, String msg) {
			lock.acquire();
			Lib.debug(dbgCommunicator, (state == s ? "[PASS]: " : "[FAIL]: ") + msg);
			lock.release();
		}
		static void updateReceived(int word) {
			received += word;
		}
		public static int getReceived() {
			return received;
		}
	}
	
	private static class TestSpeakerThread extends TestChattyThread {
		int word;

		TestSpeakerThread(int word) {
			this.word = word;
		}
		
		public void run() {
			setState(TESTSTATE.SPEAKING);
			comm.speak(word);
		}
	}
	
	private static class TestListenerThread extends TestChattyThread {
		public void run() {
			setState(TESTSTATE.LISTENING);
			updateReceived(comm.listen());
		}
	}
}
