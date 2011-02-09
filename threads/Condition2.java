package nachos.threads;

import java.util.LinkedList;

import nachos.machine.*;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 * 
 * <p>
 * You must implement this.
 * 
 * @see nachos.threads.Condition
 */
public class Condition2 {
	/**
	 * Allocate a new condition variable.
	 * 
	 * @param conditionLock
	 *            the lock associated with this condition variable. The current
	 *            thread must hold this lock whenever it uses <tt>sleep()</tt>,
	 *            <tt>wake()</tt>, or <tt>wakeAll()</tt>.
	 */
	public Condition2(Lock conditionLock) {
		this.conditionLock = conditionLock;
	}

	/**
	 * Atomically release the associated lock and go to sleep on this condition
	 * variable until another thread wakes it using <tt>wake()</tt>. The current
	 * thread must hold the associated lock. The thread will automatically
	 * reacquire the lock before <tt>sleep()</tt> returns.
	 */
	public void sleep() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		// disable machine interrupts to ensure atomic operations
		boolean status = Machine.interrupt().disable();

		// release the lock
		conditionLock.release();

		// go on the waitQueue
		waitQueue.add(KThread.currentThread());
		
		/*
		 * Sleep the current thread
		 * 
		 * If the thread is woken up prematurely, it will sleep on the lock
		 */
		KThread.sleep();

		conditionLock.acquire();

		// restore machine state
		Machine.interrupt().restore(status);
	}

	/**
	 * Wake up at most one thread sleeping on this condition variable. The
	 * current thread must hold the associated lock.
	 */
	public void wake() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		boolean intStatus = Machine.interrupt().disable();

		if (!waitQueue.isEmpty())
			waitQueue.pop().ready();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up all threads sleeping on this condition variable. The current
	 * thread must hold the associated lock.
	 */
	public void wakeAll() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());
		
		boolean intStatus = Machine.interrupt().disable();
		
		for (KThread waitingThead : waitQueue) {
			waitingThead.ready();
		}
		
		waitQueue.clear();
		
		Machine.interrupt().restore(intStatus);
	}
	
	
	/**
	 * Tests whether this module is working.
	 */
	public static void selfTest() {
		/*
		 * The only way we can test this without crashing the program is with a normal sort of test with KThread sleeping on this (just to test basic functionality)
		 */
		System.out.println("Condition2 Self Test");
		KThread thread;
		
		// Verify that only the thread that holds the condition lock can call methods
		thread = new KThread(new Runnable() {
			// Nobody holds this condition lock
			Condition2 cond = new Condition2(new Lock());
			
			public void run() {
				try {
					cond.sleep();
					System.out.println("[FAIL]: Successfully called Condition2.sleep() without holding condition lock");
				}
				catch (Error e) {
					System.out.println("[PASS]: Condition2.sleep() threw assertion when called without holding condition lock");
				}
				try {
					cond.wake();
					System.out.println("[FAIL]: Successfully called Condition2.wake() without holding condition lock");
				}
				catch (Error e) {
					System.out.println("[PASS]: Condition2.wake() threw assertion when called without holding condition lock");
				}
				try {
					cond.wakeAll();
					System.out.println("[FAIL]: Successfully called Condition2.wakeAll() without holding condition lock");
				}
				catch (Error e) {
					System.out.println("[PASS]: Condition2.wakeAll() threw assertion when called without holding condition lock");
				}
			}
		});
		thread.fork();
		thread.join();
		
		// Verify that thread reacquires lock when woken
		final Lock lock = new Lock();
		final Condition2 cond = new Condition2(lock);
		
		KThread thread1 = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				cond.sleep();
				
				// When I wake up, I should hold the lock
				System.out.println((lock.isHeldByCurrentThread() ? "[PASS]" : "[FAIL]") + ": Thread reacquires lock when woken.");
				lock.release();
			}
		});
		
		KThread thread2 = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				cond.wake();
				lock.release();
			}
		});
		
		thread1.fork();
		thread2.fork();
		thread1.join();
		
		// Verify that wake() wakes up 1 thread
		WakeCounter.wakeups = 0;
		WakeCounter.lock = lock;
		WakeCounter.cond = cond;
		
		new KThread(new WakeCounter()).fork();
		new KThread(new WakeCounter()).fork();
		thread = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				cond.wake();
				lock.release();
			}
		});
		thread.fork();
		thread.join();
		
		System.out.println((WakeCounter.wakeups == 1 ? "[PASS]" : "[FAIL]") + ": Only 1 sleeping thread woken by Condition2.wake(). (" + WakeCounter.wakeups + ")");

		// Verify that wakeAll() wakes up all threads
		WakeCounter.wakeups = 0;
		
		new KThread(new WakeCounter()).fork();
		new KThread(new WakeCounter()).fork();
		thread = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				cond.wakeAll();
				lock.release();
			}
		});
		thread.fork();
		thread.join();
		
		// Notice: this should wake up the thread that's still hanging around from the last test, in addition to the new ones.
		System.out.println((WakeCounter.wakeups == 3 ? "[PASS]" : "[FAIL]") + ": All sleeping threads woken by Condition2.wakeAll(). (" + WakeCounter.wakeups + ")");
	}
	
	/**
	 * Test class which increments a static counter when woken
	 */
	static class WakeCounter implements Runnable {
		public static int wakeups = 0;
		public static Lock lock = null;
		public static Condition2 cond = null;
		
		public void run() {
			lock.acquire();
			cond.sleep();
			wakeups++;
			lock.release();
		}
	}
	

	private Lock conditionLock;

	private LinkedList<KThread> waitQueue = new LinkedList<KThread>();
}
