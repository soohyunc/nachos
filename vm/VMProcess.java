package nachos.vm;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		if (kernel == null) {
			try {
				kernel = (VMKernel) ThreadedKernel.kernel;
			} catch (ClassCastException cce) {
				//TODO: Fail immediately. Kernel is not a vm kernel.
			}
		}
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	@Override
	public void saveState() {
		//Propagate the effect of the memory accesses
		kernel.propagateAndFlushTLB(true);
	}

	/**
	 * We need this override so it doesn't push the page table back 
	 * into the processor.
	 */
	@Override
	public void restoreState() {}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * Thunk the <tt>CoffSection</tt>s and the stack pages. The arguments are thunked in a later method.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	@Override
	protected boolean loadSections() {
		//Thunk the coff sections
		int topVPN = 0;
		for (int sectionNumber = 0; sectionNumber < coff.getNumSections(); sectionNumber++) {
			CoffSection section = coff.getSection(sectionNumber);

			CoffConstructor constructor;

			//map all of its VPNs to it
			topVPN += section.getLength();
			for (int i = section.getFirstVPN(); i < topVPN; i++) {
				constructor = new CoffConstructor(section, i);
				thunkedSections.put(i, constructor);
			}
		}

		//Now make thunks for all the stack pages
		for (; topVPN < numPages - 1; topVPN++)
			thunkedSections.put(topVPN, new StackConstructor(topVPN));

		return true;
	}
	
	@Override
	protected void unloadSections() {
		kernel.freePages(PID, numPages);
	}

	@Override
	protected void loadArguments(int entryOffset, int stringOffset, byte[][] argv) {
		thunkedSections.put(numPages - 1, new ArgConstructor(entryOffset, stringOffset, argv));
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exception</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	@Override
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionTLBMiss:
			handleTLBMiss(processor.readRegister(processor.regBadVAddr));
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	/**
	 * Handle the TLB miss associated with the virtual address.
	 * @param bad vaddr
	 */
	public void handleTLBMiss(int vaddr) {
		if (!validAddress(vaddr)) {
			//TODO: terminate process?
		} else {
			TranslationEntry retrievedTE = retrievePage(Processor.pageFromAddress(vaddr));
			
			boolean unwritten = true;
			//Invalidate all entries that point to the new physical page
			//Find preferably an invalid (i.e. empty) entry and replace it. Otherwise use random replacement.
			Processor p = Machine.processor();
			for (int i = 0; i < p.getTLBSize() && unwritten; i++) {
				TranslationEntry tlbTranslationEntry = p.readTLBEntry(i);

				//Invalidate the entry if it matches
				if (tlbTranslationEntry.ppn == retrievedTE.ppn) {
					if (unwritten) {
						p.writeTLBEntry(i, retrievedTE);
						unwritten = false;
					} else if (tlbTranslationEntry.valid) {
						tlbTranslationEntry.valid = false;
						p.writeTLBEntry(i, tlbTranslationEntry);
					}
				} else if (unwritten && !tlbTranslationEntry.valid) {
					p.writeTLBEntry(i, retrievedTE);
					unwritten = false;
				}
			}

			//Use a random replacement policy if we have not yet written it to the TLB
			if (unwritten) {
				int randomIndex = generator.nextInt(p.getTLBSize());
				TranslationEntry oldEntry = p.readTLBEntry(randomIndex);
				
				//Propagate the info down onto memory				
				if (oldEntry.dirty || oldEntry.used)
					kernel.propagateEntry(oldEntry.ppn, oldEntry.used, oldEntry.dirty);
				
				p.writeTLBEntry(randomIndex, retrievedTE);
			}
			
			//unpin the physical page
			kernel.unpin(retrievedTE.ppn);
		}
	}

	/** A generator for generating random number for TLB replacement. */
	public Random generator = new Random();

	/**
	 * Retrieve the TranslationEntry corresponding to the virtual page for this process.
	 * <p>
	 * This method will invoke lazy loading or generate a page fault as needed.
	 * @param vpn
	 * @return
	 */
	public TranslationEntry retrievePage(int vpn) {
		TranslationEntry returnEntry = null;

		//Check if we need to lazy load it
		if (thunkedSections.containsKey(vpn))
			returnEntry = thunkedSections.get(vpn).execute();
		else if ((returnEntry = kernel.pinIfExists(vpn, PID)) == null)//check to see if it exists in the mapping
			//We need to pagefault
			returnEntry = kernel.pageFault(vpn, PID);

		Lib.assertTrue(returnEntry != null);
		return returnEntry;
	}

	@Override
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		int bytesRead = 0;
		LinkedList<VMMemoryAccess> memoryAccesses = createMemoryAccesses(vaddr, data, offset, length, AccessType.READ, true);

		//Execute them if they were successfully created
		if (memoryAccesses != null) {
			int temp;
			for (VMMemoryAccess vma : memoryAccesses) {
				temp = vma.executeAccess();
				if (temp == 0)
					break;
				else
					bytesRead += temp;
			}
		}

		return bytesRead;
	}

	@Override
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		return writeVirtualMemory(vaddr, data, offset, length, true);
	}

	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length, boolean unpin) {
		int bytesWritten = 0;
		LinkedList<VMMemoryAccess> memoryAccesses = createMemoryAccesses(vaddr, data, offset, length, AccessType.WRITE, unpin);

		//Execute them if they were successfully created
		if (memoryAccesses != null) {
			int temp;
			for (VMMemoryAccess vma : memoryAccesses) {
				temp = vma.executeAccess();
				if (temp == 0)
					break;
				else
					bytesWritten += temp;
			}
		}

		return bytesWritten;
	}

	public int writeVirtualMemory(int vaddr, byte[] data, boolean unpin) {
		return VMProcess.this.writeVirtualMemory(vaddr, data, 0, data.length, unpin);
	}

	public LinkedList<VMMemoryAccess> createMemoryAccesses(int vaddr, byte[] data, int offset, int length, AccessType accessType, boolean unpin) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);
		LinkedList<VMMemoryAccess> returnList  = null;

		if (validAddress(vaddr)) {
			returnList = new LinkedList<VMMemoryAccess>();

			while (length > 0) {
				int vpn = Processor.pageFromAddress(vaddr);

				int potentialPageAccess = Processor.pageSize - Processor.offsetFromAddress(vaddr);
				int accessSize = length < potentialPageAccess ? length : potentialPageAccess;

				returnList.add(new VMMemoryAccess(accessType, data, vpn, offset, Processor.offsetFromAddress(vaddr), accessSize, unpin));
				length -= accessSize;
				vaddr += accessSize;
				offset += accessSize;
			}
		}

		return returnList;
	}

	public static final int pageSize = Processor.pageSize;
	public static final char dbgProcess = 'a';
	public static final char dbgVM = 'v';

	/** A reference to the VMKernel for convenience. */
	public static VMKernel kernel = null; // changed from public only for testing

	public HashMap<Integer,Constructor> thunkedSections = new HashMap<Integer,Constructor>();

	protected class VMMemoryAccess extends UserProcess.MemoryAccess {
		VMMemoryAccess(AccessType at, byte[] d, int _vpn, int dStart, int pStart, int len, boolean _unpin) {
			super(at,d,_vpn,dStart,pStart,len);
			unpin = _unpin;
		}

		@Override
		public int executeAccess() {
			//Overwrite the translationEntry with a new one
			translationEntry = retrievePage(vpn);//page should be pinned

			int bytesAccessed = super.executeAccess();

			//unpin the page when we're done
			if (unpin)
				kernel.unpin(translationEntry.ppn);

			return bytesAccessed;
		}

		/** A bit to indicate whether the access should unpin the page when it is finished. */
		public boolean unpin;
	}

	/**
	 * A class to thunk the initialization of pages
	 * @author Sam Whitlock
	 */
	public abstract class Constructor {
		abstract TranslationEntry execute();
	}

	public class CoffConstructor extends Constructor {
		CoffConstructor(CoffSection ce, int vpn1) {
			coffSection = ce;
			vpn = vpn1;
		}

		@Override
		TranslationEntry execute() {
			//Also, remove yourself from all the thunked section mappings you're in
			int sectionNumber = vpn - coffSection.getFirstVPN();
			Lib.assertTrue(thunkedSections.remove(vpn) != null);
			
			//Get a free page
			TranslationEntry returnEntry = kernel.requestFreePage(vpn, PID);
			coffSection.loadPage(sectionNumber, returnEntry.ppn);
			
			returnEntry.readOnly = coffSection.isReadOnly() ? true : false;

			return returnEntry;
		}

		public CoffSection coffSection;
		public int vpn;
	}

	public class StackConstructor extends Constructor {
		StackConstructor(int vpn1) {
			vpn = vpn1;
		}

		@Override
		TranslationEntry execute() {
			//Remove yourself from the mapping
			Lib.assertTrue(thunkedSections.remove(vpn) != null);

			TranslationEntry te = kernel.requestFreePage(vpn, PID);
			te.readOnly = false;
			return te;
		}

		public int vpn;
	}

	public class ArgConstructor extends Constructor {
		ArgConstructor(int _entryOffset, int _stringOffset, byte[][] _argv) {
			entryOffset = _entryOffset; stringOffset = _stringOffset; argv = _argv;
		}

		@Override
		TranslationEntry execute() {
			Lib.assertTrue(thunkedSections.remove(numPages - 1) != null);

			TranslationEntry te = kernel.requestFreePage(numPages - 1, PID);//get a free page

			//The page is pinned, so just use writeVM to load in the info
			for (int i = 0; i < argv.length; i++) {
				byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
				Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes, false) == 4);
				entryOffset += 4;
				Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i], false) == argv[i].length);
				stringOffset += argv[i].length;
				Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }, false) == 1);
				stringOffset += 1;
			}
			
			te.readOnly = true;
			
			return te;
		}

		public int entryOffset, stringOffset;
		public byte[][] argv;
	}
}
