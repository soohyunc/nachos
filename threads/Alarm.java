package nachos.threads;

import nachos.machine.*;

import java.util.TreeMap;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	
	private TreeMap<Long, KThread> waitingThreads;
	
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		waitingThreads = new TreeMap<Long, KThread>();
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		// Readying threads is an atomic operation
		boolean intStatus = Machine.interrupt().disable();

		// Ready all threads whose wait time has expired
		long curTime = Machine.timer().getTime();
		while (!waitingThreads.isEmpty() && waitingThreads.firstKey() <= curTime)
			waitingThreads.pollFirstEntry().getValue().ready();
		
		Machine.interrupt().restore(intStatus);

		// Preempt current thread as normal
		KThread.yield();
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x
	 *            the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		// Sleeping thread is an atomic operation
		boolean intStatus = Machine.interrupt().disable();

		// Place current thread on a wait queue and put it to sleep
		waitingThreads.put(Machine.timer().getTime() + x, KThread.currentThread());
		KThread.sleep();
		
		Machine.interrupt().restore(intStatus);
	}
	

	private static final char dbgAlarm = 'a'; 	// Flag to enable Alarm debug output
	/**
	 * Run sanity check on Alarm.waitUntil
	 */
	public static void selfTest() {
		Lib.debug(dbgAlarm, "Alarm Self Test");
		
		// Test that alarm wakes up thread after proper amount of time
		KThread thread = new KThread(new Runnable() {
			public void run() {
				final long ticks = 1000;
				long sleepTime = Machine.timer().getTime();
				ThreadedKernel.alarm.waitUntil(ticks);
				long wakeTime = Machine.timer().getTime();
				
				Lib.debug(dbgAlarm, (((wakeTime-sleepTime>=ticks) ? "[PASS]" : "[FAIL]") + ": Thread slept at least " + ticks + " ticks " + sleepTime + "->" + wakeTime));
			}
		});
		thread.fork();
		thread.join();
		
		// Test that several sleeping threads wake up in order
		KThread threadA = new KThread(new TestSeqThread('A',100));
		KThread threadB = new KThread(new TestSeqThread('B',700));
		KThread threadC = new KThread(new TestSeqThread('C',1400));

		threadA.fork(); threadB.fork(); threadC.fork();
		threadA.join(); threadB.join(); threadC.join();
		
		Lib.debug(dbgAlarm, (TestSeqThread.wakeSequence.equals("ABC") ? "[PASS]" : "[FAIL") + ": Threads woke up in order (" + TestSeqThread.wakeSequence + ")");
	}
	
	/**
	 * For testing:
	 * Thread which immediately sleeps and keeps a static record
	 * of the order in which it and its siblings wake up
	 */
	private static class TestSeqThread implements Runnable {
		char myName;
		long mySleepTicks;
		
		static String wakeSequence = "";
		static Lock lock = new Lock();
		
		public TestSeqThread(char name, long sleepTicks) {
			myName = name;
			mySleepTicks = sleepTicks;
		}
		
		public void run() {
			ThreadedKernel.alarm.waitUntil(mySleepTicks);
			lock.acquire();
			wakeSequence = wakeSequence + myName;
			lock.release();
		}
	}
}
