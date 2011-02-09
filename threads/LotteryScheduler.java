package nachos.threads;

import java.util.HashSet;
import java.util.Random;

import nachos.machine.*;

/**
 * A scheduler that chooses threads using a lottery.
 * 
 * <p>
 * A lottery scheduler associates a number of tickets with each thread. When a
 * thread needs to be dequeued, a random lottery is held, among all the tickets
 * of all the threads waiting to be dequeued. The thread that holds the winning
 * ticket is chosen.
 * 
 * <p>
 * Note that a lottery scheduler must be able to handle a lot of tickets
 * (sometimes billions), so it is not acceptable to maintain state for every
 * ticket.
 * 
 * <p>
 * A lottery scheduler must partially solve the priority inversion problem; in
 * particular, tickets must be transferred through locks, and through joins.
 * Unlike a priority scheduler, these tickets add (as opposed to just taking the
 * maximum).
 */
public class LotteryScheduler extends Scheduler {
	/**
	 * Allocate a new lottery scheduler.
	 */
	public LotteryScheduler() {
	}

	public int getPriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getLotteryThreadState(thread).getTickets();
	}

	public int getEffectivePriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getLotteryThreadState(thread).getEffectiveTickets();
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum && priority <= priorityMaximum);

		LotteryThreadState lts = getLotteryThreadState(thread);

		//To make sure we don't do unnecessary calculation
		if (priority != lts.getTickets())
			lts.setTickets(priority);
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
	static final int priorityDefault = 1;

	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	static final int priorityMinimum = 1;

	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	static final int priorityMaximum = Integer.MAX_VALUE;

	/**
	 * Return the scheduling state of the specified thread.
	 * 
	 * @param thread
	 *            the thread whose scheduling state to return.
	 * @return the scheduling state of the specified thread.
	 */
	protected LotteryThreadState getLotteryThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new LotteryThreadState(thread);

		return (LotteryThreadState) thread.schedulingState;
	}

	@Override
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new LotteryQueue(transferPriority);
	}

	protected class LotteryQueue extends ThreadQueue {

		LotteryQueue(boolean transferTickets2) {
			transferTickets = transferTickets2;
		}

		@Override
		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getLotteryThreadState(thread).waitForAccess(this);
		}

		@Override
		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());
			if (waiting.isEmpty())
				return null;
			else {
				int ticketIndex = randomGenerator.nextInt(totalEffectiveTickets)+1;

				KThread returnThread = null;

				for (LotteryThreadState lts : waiting) {
					ticketIndex -= lts.getEffectiveTickets();

					if (ticketIndex <= 0) {
						returnThread = lts.thread;
						lts.acquire(this);
						break;
					}
				}

				return returnThread;
			}
		}

		@Override
		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getLotteryThreadState(thread).acquire(this);
		}

		@Override//You don't need to implement this unless you want
		public void print() {
		}

		/**
		 * The thread that locks (i.e. acquires) <tt>this</tt>.
		 */
		private LotteryThreadState lockingThread; 

		/**
		 * A set of things that have locked this.
		 */
		private HashSet<LotteryThreadState> waiting = new HashSet<LotteryThreadState>();

		/**
		 * A boolean to indicate whether or not <tt>this</tt> should implement priority donation.
		 */
		private boolean transferTickets;

		/**
		 * The total number of effective tickets of all the things in <tt>waiting</tt>.
		 */
		private int totalEffectiveTickets;

		/**
		 * A random number generator for holding the lottery.
		 */
		Random randomGenerator = new Random();

		/**
		 * A "dumb" method that merely gets all the effective ticket counts from things waiting in <tt>this</tt>.
		 */
		void updateEffectiveTickets() {
			int temp = 0;
			for (LotteryThreadState lts : waiting)
				temp += lts.getEffectiveTickets();
			totalEffectiveTickets = temp;
		}

		void removeFromWaiting(LotteryThreadState lotteryThreadState) {
			if (waiting.remove(lotteryThreadState)) {
				updateEffectiveTickets();//TODO: probably can do a shorter update in the future

				if (lockingThread != null)
					lockingThread.fullUpdateEffectiveTickets();
			}
		}
	}

	protected static class LotteryThreadState {

		LotteryThreadState(KThread thread2) {
			thread = thread2;
		}

		/**
		 * Add the parameter <tt>LotteryQueue</tt> to the list of things that <tt>this</tt> has acquired.
		 * @param lotteryQueue
		 */
		void acquire(LotteryQueue lotteryQueue) {
			if (lotteryQueue.lockingThread != this) {//To prevent unnecessary computation for strange calls
				//Clean out the old one, if it exists
				if (lotteryQueue.lockingThread != null)
					lotteryQueue.lockingThread.release(lotteryQueue);

				//TODO: Need to remove this from the lotteryQueue's wait queue
				lotteryQueue.removeFromWaiting(this);

				lotteryQueue.lockingThread = this;

				acquired.add(lotteryQueue);
				waiting.remove(lotteryQueue);

				fullUpdateEffectiveTickets();
			}
		}

		/**
		 * Release this lottery queue from those <tt>this</tt> has acquired in <tt>acquired</tt>.
		 * @param lotteryQueue
		 */
		private void release(LotteryQueue lotteryQueue) {
			if (lotteryQueue.lockingThread == this) {
				if (!acquired.remove(lotteryQueue))
					System.out.println("Error : Release something not in the acquired set");
				lotteryQueue.lockingThread = null;
				fullUpdateEffectiveTickets();
			}
		}

		/**
		 * Add <tt>this</tt> to the parameter <tt>LotteryQueue</tt>'s list of waiting threads and propagate accordingly.
		 * @param lotteryQueue
		 */
		void waitForAccess(LotteryQueue lotteryQueue) {
			release(lotteryQueue);//Releases the lotteryQueue iff this has acquired it. No checks required at this line
			if (!lotteryQueue.waiting.contains(this)) {
				waiting.add(lotteryQueue);
				lotteryQueue.waiting.add(this);

				//This check prevents a more expensive check during fullUpdateEffectivePriority
				if (lotteryQueue.transferTickets && lotteryQueue.lockingThread != null)
					lotteryQueue.lockingThread.fullUpdateEffectiveTickets();
				else
					lotteryQueue.updateEffectiveTickets();//You may be able to get away with something quicker
			}
		}

		/**
		 * @return The effective number of tickets that <tt>this</tt> has, considering all donations. 
		 */
		int getEffectiveTickets() {
			return effectiveTickets;
		}

		/**
		 * @return The number of tickets that <tt>this</tt> actually has, <b>not</b> including donations.
		 */
		int getTickets() {
			return tickets;
		}

		/**
		 * Set the actual number of tickets of <tt>this</tt>
		 * <p>
		 * This assumes that the number of tickets does not exceed the bounds, as it is only called from <tt>LotteryScheduler</tt>.
		 * @param tickets
		 */
		void setTickets(int tickets2) {
			tickets = tickets2;
			fullUpdateEffectiveTickets();//FIXME: Maybe we can get away with a propagation?
		}

		/**
		 * Update the number of effective tickets that <tt>this</tt> has. 
		 * <P>
		 * The formula is <tt>
		 * tickets + for each LotteryQueue in acquired { for each LotteryThreadState in acquired : accum sum }
		 * </tt>
		 */
		private void fullUpdateEffectiveTickets() {
			int temp = tickets;

			for (LotteryQueue lq : acquired)
				if (lq.transferTickets)
					for (LotteryThreadState lts : lq.waiting)
						temp += lts.effectiveTickets;

			effectiveTickets = temp;

			for (LotteryQueue lq : waiting) {
				lq.updateEffectiveTickets();//TODO: maybe this can be a faster call in the future
				if (lq.transferTickets && lq.lockingThread != null)
					lq.lockingThread.fullUpdateEffectiveTickets();
			}
		}

		/**
		 * A set of <tt>LotteryQueue</tt>s that <tt>this</tt> has acquired.
		 */
		private HashSet<LotteryQueue> acquired = new HashSet<LotteryQueue>();

		/**
		 * A set of <tt>LotteryQueue</tt>s that <tt>this</tt> is waiting in.
		 */
		private HashSet<LotteryQueue> waiting = new HashSet<LotteryQueue>();

		/**
		 * The actual number of tickets that <tt>this</tt> contains.
		 */
		private int tickets = priorityDefault;

		/**
		 * The effective number of tickets that <tt>this</tt> contains, including donations from 
		 * queues that <tt>this</tt> has acquired.
		 */
		private int effectiveTickets = priorityDefault;

		/**
		 * The <tt>KThread</tt> instance that <tt>this</tt> represents.
		 */
		private KThread thread;
	}

	public static void selfTest() {
		if (ThreadedKernel.scheduler != null && ThreadedKernel.scheduler instanceof LotteryScheduler) {
			boolean oldValue = Machine.interrupt().disable();
			noDonation();
			withDonation();
			Machine.interrupt().restore(oldValue);
		}
	}

	private static void withDonation() {
		KThread k1 = new KThread(), k2 = new KThread(), k3 = new KThread(), k4 = new KThread();
		LotteryQueue lq1 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(true), lq2 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(true), lq3 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(true);

		lq1.acquire(k1);
		lq2.acquire(k2);
		lq3.acquire(k3);

		lq1.waitForAccess(k2);
		lq2.waitForAccess(k3);
		lq3.waitForAccess(k4);

		((LotteryThreadState)k1.schedulingState).setTickets(10);
		((LotteryThreadState)k2.schedulingState).setTickets(10);
		((LotteryThreadState)k3.schedulingState).setTickets(10);
		((LotteryThreadState)k4.schedulingState).setTickets(10);

		Lib.assertTrue(((LotteryThreadState)k1.schedulingState).getEffectiveTickets() == 40 && ((LotteryThreadState)k2.schedulingState).getEffectiveTickets() == 30 && ((LotteryThreadState)k3.schedulingState).getEffectiveTickets() == 20 && ((LotteryThreadState)k4.schedulingState).getEffectiveTickets() == 10);
	}

	private static void noDonation() {
		//DO NOT TEST CIRCULAR CASES
		//We do not have to handle those and they cause crashes

		//First test with no priority donation
		KThread k1 = new KThread(), k2 = new KThread(), k3 = new KThread(), k4 = new KThread();
		LotteryQueue lq1 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(false), lq2 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(false), lq3 = (LotteryQueue)ThreadedKernel.scheduler.newThreadQueue(false);

		lq1.acquire(k1);
		lq2.acquire(k2);
		lq3.acquire(k3);

		lq1.waitForAccess(k2);
		lq2.waitForAccess(k3);
		lq3.waitForAccess(k4);

		((LotteryThreadState)k1.schedulingState).setTickets(100);
		((LotteryThreadState)k2.schedulingState).setTickets(100);
		((LotteryThreadState)k3.schedulingState).setTickets(100);
		((LotteryThreadState)k4.schedulingState).setTickets(100);

		Lib.assertTrue(((LotteryThreadState)k1.schedulingState).getEffectiveTickets() == 100 && ((LotteryThreadState)k2.schedulingState).getEffectiveTickets() == 100 && ((LotteryThreadState)k3.schedulingState).getEffectiveTickets() == 100 && ((LotteryThreadState)k4.schedulingState).getEffectiveTickets() == 100);
	}
}
