package nachos.threads;

import nachos.machine.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;

/**
 * A scheduler that chooses threads based on their priorities.
 * 
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the thread
 * that has been waiting longest.
 * 
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fashion to
 * all the highest-priority threads, and ignores all other threads. This has the
 * potential to starve a thread if there's always a thread waiting with higher
 * priority.
 * 
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
	/**
	 * Allocate a new priority scheduler.
	 */
	public PriorityScheduler() {
	}

	/**
	 * Allocate a new priority thread queue.
	 * 
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer priority from
	 *            waiting threads to the owning thread.
	 * @return a new priority thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new PriorityQueue(transferPriority);
	}

	public int getPriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getPriority();
	}

	public int getEffectivePriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getEffectivePriority();
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum && priority <= priorityMaximum);
		
		ThreadState ts = getThreadState(thread);
		
		//To make sure we don't do unnecessary calculation
		if (priority != ts.getPriority())
			ts.setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable(), returnBool = true;

		KThread thread = KThread.currentThread();
		
		int priority = getPriority(thread);
		if (priority == priorityMaximum)
			returnBool = false;
		else
			setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return returnBool;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable(), returnBool = true;
		
		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum)
			returnBool = false;
		else
			setPriority(thread, priority - 1);
		
		Machine.interrupt().restore(intStatus);
		return returnBool;
	}

	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 0;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = 7;

	/**
	 * Return the scheduling state of the specified thread.
	 * 
	 * @param thread
	 *            the thread whose scheduling state to return.
	 * @return the scheduling state of the specified thread.
	 */
	protected ThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new ThreadState(thread);

		return (ThreadState) thread.schedulingState;
	}

	/**
	 * A <tt>ThreadQueue</tt> that sorts threads by priority.
	 */
	protected class PriorityQueue extends ThreadQueue {
		PriorityQueue(boolean transferPriority) {
			this.transferPriority = transferPriority;
		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).acquire(this);
		}

		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());
			if (waitQueue.isEmpty()) {
				return null;
			} else {
				acquire(waitQueue.poll().thread);

				return lockingThread;
			}
		}

		/**
		 * Return the next thread that <tt>nextThread()</tt> would return,
		 * without modifying the state of this queue.
		 * 
		 * @return the next thread that <tt>nextThread()</tt> would return.
		 */
		protected ThreadState pickNextThread() {
			return waitQueue.peek();
		}

		public void print() {
		}

		/**
		 * <tt>true</tt> if this queue should transfer priority from waiting
		 * threads to the owning thread.
		 */
		boolean transferPriority;
		
		/**
		 * The java.util.Priority queue that underlies this Priority Queue
		 */
		private java.util.PriorityQueue<ThreadState> waitQueue = new java.util.PriorityQueue<ThreadState>(8,new ThreadStateComparator<ThreadState>(this));
		/**
		 * The <tt>KThread</tt> that locks this PriorityQueue. Initially it is null.
		 */
		private KThread lockingThread = null;
		
		protected class ThreadStateComparator<T extends ThreadState> implements Comparator<T> {
			protected ThreadStateComparator(nachos.threads.PriorityScheduler.PriorityQueue pq) {
				priorityQueue = pq;
			}
			
			@Override
			public int compare(T o1, T o2) {
				//first compare by effective priority
				int o1_effectivePriority = o1.getEffectivePriority(), o2_effectivePriority = o2.getEffectivePriority();
				if (o1_effectivePriority > o2_effectivePriority) {
					return -1;
				} else if (o1_effectivePriority < o2_effectivePriority) {
					return 1;
				} else {
					//compare by the times these threads have spent in this queue
					long o1_waitTime = o1.waiting.get(priorityQueue), o2_waitTime = o2.waiting.get(priorityQueue);
					/*
					 * Note: the waiting maps should contain THIS at this point. If they don't then there is an error
					 */
					if (o1_waitTime < o2_waitTime) {
						return -1;
					} else if(o1_waitTime > o2_waitTime) {
						return 1;
					} else {
						//The threads are equal (and probably the same thread)
						return 0;
					}
				}
			}
			
			private nachos.threads.PriorityScheduler.PriorityQueue priorityQueue;
		}
	}

	/**
	 * The scheduling state of a thread. This should include the thread's
	 * priority, its effective priority, any objects it owns, and the queue it's
	 * waiting for, if any.
	 * 
	 * @see nachos.threads.KThread#schedulingState
	 */
	protected class ThreadState {
		/**
		 * Allocate a new <tt>ThreadState</tt> object and associate it with the
		 * specified thread.
		 * 
		 * @param thread
		 *            the thread this state belongs to.
		 */
		ThreadState(KThread thread) {
			this.thread = thread;
			
			effectivePriority = priorityDefault;
			setPriority(priorityDefault);//FIXME: Is this really necessary? At this point it is not linked above or below by anything.
		}

		/**
		 * Release this priority queue from the resources this ThreadState has locked.
		 * <p>
		 * This is the only time the effective priority of a thread can go down and needs a full recalculation.
		 * <p>
		 * We can detect if this exists if the top effective priority of the queue we are release is equal to this current effective priority.
		 * If it is less than (it cannot be greater by definition), then we know that something else is contributing to the effective priority of <tt>this</tt>.
		 * @param priorityQueue
		 */
		private void release(PriorityQueue priorityQueue) {
			// remove priorityQueue from my acquired set
			if (acquired.remove(priorityQueue)) {
				priorityQueue.lockingThread = null;
				updateEffectivePriority();
			}
		}

		/**
		 * Return the priority of the associated thread.
		 * 
		 * @return the priority of the associated thread.
		 */
		int getPriority() {
			return priority;
		}

		/**
		 * Return the effective priority of the associated thread.
		 * 
		 * @return the effective priority of the associated thread.
		 */
		int getEffectivePriority() {
			return effectivePriority;
		}

		/**
		 * Set the priority of the associated thread to the specified value. <p>
		 * This method assumes the priority has changed. Protection is from PriorityScheduler class calling this.
		 * @param priority
		 *            the new priority.
		 */
		void setPriority(int priority) {
			this.priority = priority;
			updateEffectivePriority();
		}

		protected void updateEffectivePriority() {
			for (PriorityQueue pq : waiting.keySet())
				pq.waitQueue.remove(this);
			
			int tempPriority = priority;
			
			for (PriorityQueue pq : acquired) {
				if (pq.transferPriority) {
					ThreadState topTS = pq.waitQueue.peek();
					if (topTS != null) {
						int topPQ_AP = topTS.getEffectivePriority();
						
						if (topPQ_AP > tempPriority)
							tempPriority = topPQ_AP;
					}
				}
			}
			
			boolean needToTransfer = tempPriority != effectivePriority;
			
			effectivePriority = tempPriority;
			
			/*
			 * Add this back in and propagate up all the results
			 */		
			for (PriorityQueue pq : waiting.keySet())
				pq.waitQueue.add(this);

			if (needToTransfer)
				for (PriorityQueue pq : waiting.keySet()) {
					if (pq.transferPriority && pq.lockingThread != null)
						getThreadState(pq.lockingThread).updateEffectivePriority();
				}
		}

		/**
		 * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
		 * the associated thread) is invoked on the specified priority queue.
		 * The associated thread is therefore waiting for access to the resource
		 * guarded by <tt>waitQueue</tt>. This method is only called if the
		 * associated thread cannot immediately obtain access.
		 * 
		 * @param priorityQ
		 *            the queue that the associated thread is now waiting on.
		 * 
		 * @see nachos.threads.ThreadQueue#waitForAccess
		 */
		void waitForAccess(PriorityQueue priorityQ) {
			if (!waiting.containsKey(priorityQ)) {
				//Unlock this wait queue, if THIS holds it
				release(priorityQ);
				
				//Put it on the queue
				waiting.put(priorityQ, Machine.timer().getTime());
				
				//The effective priority of this shouldn't change, so just shove it onto the waitQueue's members
				priorityQ.waitQueue.add(this);
				
				if (priorityQ.lockingThread != null) {
					getThreadState(priorityQ.lockingThread).updateEffectivePriority();
				}
			}
		}

		/**
		 * Called when the associated thread has acquired access to whatever is
		 * guarded by <tt>waitQueue</tt>. This can occur either as a result of
		 * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
		 * <tt>thread</tt> is the associated thread), or as a result of
		 * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
		 * 
		 * @see nachos.threads.ThreadQueue#acquire
		 * @see nachos.threads.ThreadQueue#nextThread
		 */
		void acquire(PriorityQueue priorityQ) {
			//Unlock the current locking thread
			if (priorityQ.lockingThread != null) {
				getThreadState(priorityQ.lockingThread).release(priorityQ);
			}
			
			/*
			 * Remove the passed thread state from the queues, if it exists on them
			 */
			priorityQ.waitQueue.remove(this);
			
			//Acquire the thread
			priorityQ.lockingThread = this.thread;
			acquired.add(priorityQ);
			waiting.remove(priorityQ);
			
			updateEffectivePriority();
		}

		/** The thread with which this object is associated. */
		protected KThread thread;
		/** The priority of the associated thread. */
		protected int priority;
		protected int effectivePriority;
		
		/** A set of all the PriorityQueues this ThreadState has acquired */
		private HashSet<nachos.threads.PriorityScheduler.PriorityQueue> acquired = new HashSet<nachos.threads.PriorityScheduler.PriorityQueue>();
		
		/** A map of all the PriorityQueues this ThreadState is waiting on mapped to the time they were waiting on them*/
		private HashMap<nachos.threads.PriorityScheduler.PriorityQueue,Long> waiting = new HashMap<nachos.threads.PriorityScheduler.PriorityQueue,Long>();
	}
	
	public static void selfTest() {
		ThreadQueue tq1 = ThreadedKernel.scheduler.newThreadQueue(true), tq2 = ThreadedKernel.scheduler.newThreadQueue(true), tq3 = ThreadedKernel.scheduler.newThreadQueue(true);
		KThread kt_1 = new KThread(), kt_2 = new KThread(), kt_3 = new KThread(), kt_4 = new KThread();
		
		boolean status = Machine.interrupt().disable();
		
		tq1.waitForAccess(kt_1);
		tq2.waitForAccess(kt_2);
		tq3.waitForAccess(kt_3);
		
		tq1.acquire(kt_2);
		tq2.acquire(kt_3);
		tq3.acquire(kt_4);
		
		ThreadedKernel.scheduler.setPriority(kt_1, 6);
		
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(kt_4)==6);
		
		KThread kt_5 = new KThread();
		
		ThreadedKernel.scheduler.setPriority(kt_5, 7);
		
		tq1.waitForAccess(kt_5);
		
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(kt_4)==7);
		
		tq1.nextThread();
		
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(kt_4)==1);
		
		Machine.interrupt().restore(status);
	}
}
