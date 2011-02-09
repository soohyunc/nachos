package nachos.vm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();

		//Initialize the coremap by physical page number
		for (int i = 0; i < coremap.length; i++)
			coremap[i] = new MemoryEntry(i);
	}

	/**
	 * Initialize this kernel.
	 */
	@Override
	public void initialize(String[] args) {
		super.initialize(args);
		memoryLock = new Lock();
		allPinned = new Condition(memoryLock);
		swap = new Swap();
	}

	/**
	 * Start running user programs.
	 */
	@Override
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	@Override
	public void terminate() {
		// Delete the swap file
		swap.cleanup();
		
		super.terminate();
	}

	/**
	 * Select a page to be swapped out, and pin it.
	 * @return ppn
	 */
	private MemoryEntry clockAlgorithm() {
		memoryLock.acquire();
		while (pinnedCount == coremap.length) allPinned.sleep();

		/*
		 * We only need to flush before we start the search.
		 */
		propagateAndFlushTLB(false);
		
		// When we get here, there MUST be a non-pinned page to find
		while (true) {
			clockHand = (clockHand+1) % coremap.length;
			MemoryEntry page = coremap[clockHand];

			// Skip pinned pages
			if (page.pinned)
				continue;

			// Prefer invalid pages
			if (page.processID == -1 || page.translationEntry.valid == false)
				break;

			// If used recently continue
			if (page.translationEntry.used) {
				page.translationEntry.used = false;
			}
			// Otherwise use this page!
			else {
				break;
			}
		}

		MemoryEntry me = coremap[clockHand];
		pinnedCount++;//we don't have to check if it is pinned because it should only be unpinned at this point
		me.pinned = true;

		invalidateTLBEntry(clockHand);

		//Remove the mapping from the page table
		MemoryEntry me1 = null;
		if (me.processID > -1)
			me1 = invertedPageTable.remove(new TableKey(me.translationEntry.vpn, me.processID));

		memoryLock.release();

		//swap out the page here if the page had existed in memory
		//FIXME: Will we always need to swap out?
		if (me1 != null) swap.swapOut(me);

		return me;
	}

	/**
	 * Use the nth chance algorithm to free up a physical page, performing any swapping necessary.
	 * Inserts the appropriate mapping into the coremap and the inverted page table.
	 * The page will be pinned.
	 * @param vpn
	 * @param pid
	 * @return TranslationEntry of the newly freed page
	 */
	TranslationEntry requestFreePage(int vpn, int pid) {
		// Select and pin page to swap
		MemoryEntry page = clockAlgorithm();

		//Zero out the page for security
		int pageBeginAddress = Processor.makeAddress(page.translationEntry.ppn, 0);
		Arrays.fill(Machine.processor().getMemory(), pageBeginAddress, pageBeginAddress + Processor.pageSize, (byte) 0);

		// Set page attributes
		page.translationEntry.vpn = vpn;
		page.translationEntry.valid = true;
		page.processID = pid;

		// Add to inverted page table
		insertIntoTable(vpn, pid, page);

		return page.translationEntry;
	}

	private void insertIntoTable(int vpn, int pid, MemoryEntry page) {
		memoryLock.acquire();
		invertedPageTable.put(new TableKey(vpn, pid), page);
		memoryLock.release();
	}

	/**
	 * Called when a page is requested that is no longer in the inverted page table. 
	 * Swaps the page into memory if it exists, and update the coremap and inverted 
	 * page table to reflect this change.
	 * The page will be pinned.
	 * @param vpn
	 * @param pid
	 * @return TranslationEntry of the newly swapped page, or null if not found
	 */
	TranslationEntry pageFault(int vpn, int pid) {
		if (!swap.pageInSwap(vpn, pid))
			return null;
		TranslationEntry te = requestFreePage(vpn, pid);
		swap.swapIn(vpn, pid, te.ppn);

		return te;
	}

	/**
	 * Purge all pages in memory.
	 * <p>
	 * Purge all swapped pages from the given process from the swap file.
	 * @param vpn
	 * @param pid
	 */
	void freePages(int pid, int maxVPN) {
		memoryLock.acquire();
		for (MemoryEntry page : coremap)
			if (page.processID == pid) {
				// Remove from inverted page table
				invertedPageTable.remove(new TableKey(page.translationEntry.vpn, page.processID));

				// Invalidate coremap entry
				page.processID = -1;
				page.translationEntry.valid = false;
			}

		memoryLock.release();
		
		swap.freePages(maxVPN, pid);
	}

	/**
	 * Unpin the page corresponding to the physical page number.
	 * @param ppn
	 */
	void unpin(int ppn) {
		memoryLock.acquire();
		MemoryEntry me = coremap[ppn];

		if (me.pinned)
			pinnedCount--;

		me.pinned = false;

		allPinned.wake();

		memoryLock.release();
	}

	/**
	 * Pin the page if it exists.
	 * @param vpn
	 * @param pid
	 * @return true if the page exists (and was pinned)
	 */
	TranslationEntry pinIfExists(int vpn, int pid) {
		MemoryEntry me = null;
		memoryLock.acquire();

		if ((me = invertedPageTable.get(new TableKey(vpn, pid))) != null) {
			//increased the pinned count, if applicable
			if (!me.pinned)
				pinnedCount++;
			me.pinned = true;
		}

		memoryLock.release();

		if (me == null)
			return null;
		else
			return me.translationEntry;
	}

	/**
	 * Propagates the used and dirty bits from the TLB into the corresponding 
	 * physical pages.
	 * <p>
	 * Flushes the TLB by marking all entries as invalid.
	 * <p>
	 * Must be called with interrupts disabled.
	 */
	void propagateAndFlushTLB(boolean flush) {
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry te = Machine.processor().readTLBEntry(i);

			if (te.valid) {
				TranslationEntry translationEntry = coremap[te.ppn].translationEntry;
				if (translationEntry.valid && translationEntry.vpn == te.vpn) {
					//propagate effect back to memory
					translationEntry.used |= te.used;
					translationEntry.dirty |= te.dirty;
				}
			}

			if (flush) {
				te.valid = false;
				Machine.processor().writeTLBEntry(i, te);
			}
		}
	}

	/**
	 * Set the invalid bit for the TLB entry for the given ppn
	 */
	void invalidateTLBEntry(int ppn) {
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry te = Machine.processor().readTLBEntry(i);
			if (te.valid && te.ppn == ppn) {
				te.valid = false;
				Machine.processor().writeTLBEntry(i, te);
				break;
			}
		}
	}
	
	void propagateEntry(int ppn, boolean used, boolean dirty) {
		memoryLock.acquire();
		TranslationEntry te = coremap[ppn].translationEntry;
		te.used |= used;
		te.dirty |= dirty;
		memoryLock.release();
	}

	private static final char dbgVM = 'v';

	/** The coremap indexed by physical page number */
	private MemoryEntry[] coremap = new MemoryEntry[Machine.processor().getNumPhysPages()];

	/** Persistent index to current page to use in clock algorithm */
	private int clockHand = 0;

	/** A mapping from vaddr,PID to PPN */
	private Hashtable<TableKey,MemoryEntry> invertedPageTable = new Hashtable<TableKey,MemoryEntry>();

	/** A lock to protected the memory mappings. */
	private Lock memoryLock;

	/** The number of memory entries that are pinned. */
	private int pinnedCount;

	/** A condition for all processes to wait on if there are no unpinned pages.*/
	private Condition allPinned;

	/** An inner class to act as a key for the inverted page table. */
	private static class TableKey {
		TableKey(int vpn1, int pid1) {
			vpn = vpn1;
			pid = pid1;
		}

		@Override
		public int hashCode() {
			return Processor.makeAddress(vpn, pid );//There should be no collisions with this hash
		}

		@Override
		public boolean equals(Object x) {
			if (this == x)
				return true;
			else if (x instanceof TableKey) {
				TableKey xCasted = (TableKey)x;
				return vpn.equals(xCasted.vpn) && pid.equals(xCasted.pid);
			} else {
				return false;
			}
		}

		private Integer vpn, pid;
	}

	/** A class to represent the entries in the coremap */
	private static class MemoryEntry {
		MemoryEntry (int ppn) {
			translationEntry = new TranslationEntry(-1, ppn, false, false, false, false);
		}

		TranslationEntry translationEntry;
		int processID = -1;
		boolean pinned = false;
	}

	/**
	 * A method created to enable overriding for naming collision in 
	 * <tt>NetKernel</tt>.
	 * @return the open swapfile
	 */
	protected OpenFile openSwapFile() {
		return fileSystem.open("swapfile", true);
	}

	private class Swap {
		Swap() {
			swapFile = openSwapFile();
		}

		/** 
		 * Writes physical page to swap file if it isn't already swapped, or if it is dirty 
		 * NOTE: Physical page should be pinned for safety
		 */
		void swapOut(MemoryEntry me) {
			if (me.translationEntry.valid) {
				
				SwapEntry swapEntry = null;
				TableKey tk = new TableKey(me.translationEntry.vpn, me.processID);

				swapLock.acquire();
				if (me.translationEntry.dirty || !swapTable.containsKey(tk)) {
					// Use a free position if available
					if (freeList.size() > 0) {
						swapEntry = freeList.removeFirst();
						swapEntry.readOnly = me.translationEntry.readOnly;
					}
					// Otherwise extend the swap file
					else {
						swapEntry = new SwapEntry(maxTableEntry++, me.translationEntry.readOnly); 
					}

					swapTable.put(tk, swapEntry);
				}
				swapLock.release();

				if (swapEntry != null) {
					// Write the physical page
					Lib.assertTrue(swapFile.write(swapEntry.swapPageNumber * Processor.pageSize,
							Machine.processor().getMemory(),
							me.translationEntry.ppn * Processor.pageSize,
							Processor.pageSize) == Processor.pageSize);
				}
			}
		}
		
		private int maxTableEntry = 0;

		/** 
		 * Read a virtual page from the swap file and write it to physical memory
		 * NOTE: Physical page should be pinned for safety
		 */
		void swapIn(int vpn, int pid, int ppn) {
			swapLock.acquire();
			SwapEntry swapEntry = swapTable.get(new TableKey(vpn, pid));
			swapLock.release();
			
			if (swapEntry != null) {
				// Read in the physical page
				Lib.assertTrue(swapFile.read(swapEntry.swapPageNumber * Processor.pageSize,
						Machine.processor().getMemory(),
						ppn * Processor.pageSize,
						Processor.pageSize) == Processor.pageSize);

				// Restore permissions
				//We don't need to acquire a lock because the page is already pinned
				coremap[ppn].translationEntry.readOnly = swapEntry.readOnly;
			}
		}

		/**
		 * @return True if the given page is in the swap file
		 */
		boolean pageInSwap(int vpn, int pid) {
			swapLock.acquire();
			boolean retBool = swapTable.containsKey(new TableKey(vpn, pid));
			swapLock.release();
			return retBool;
		}

		/**
		 * Free page entries in the swap file associated with the process so it can be reused.
		 * (Places page on the free list)
		 * @param maxVPN: the highest VPN of the process + 1
		 */
		void freePages(int maxVPN, int pid) {
			swapLock.acquire();
			SwapEntry freeEntry;
			for (int i = 0; i < maxVPN; i++)
				if ((freeEntry = swapTable.get(new TableKey(i, pid))) != null)
					freeList.add(freeEntry);
			swapLock.release();
		}

		/**
		 * Close and delete the swap file
		 */
		void cleanup() {
			swapFile.close();
			fileSystem.remove(swapFile.getName());
		}

		/** A class to represent the location of swapped out pages in the swap file */
		private class SwapEntry {
			SwapEntry (int spn, boolean ro) {
				swapPageNumber = spn;
				readOnly = ro;
			}
			int swapPageNumber;
			boolean readOnly;
		}

		/** Reference to file which contains swapped out pages 
		 *  The file is divided into page sized chunks which are indexed by SwapEntries 
		 */
		private OpenFile swapFile;

		/** List of currently unused positions in the swap file */
		private LinkedList<SwapEntry> freeList = new LinkedList<SwapEntry>();

		/** Mapping between process pages and where they reside in the swap file */
		private HashMap<TableKey, SwapEntry> swapTable = new HashMap<TableKey, SwapEntry>();

		/** A <tt>Lock</tt> to ensure exclusive access to swapping methods. */
		private Lock swapLock = new Lock();
	}

	private Swap swap;
}
