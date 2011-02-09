package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.UserKernel.InadequatePagesException;

import java.io.EOFException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		sharedStateLock.acquire();
		PID = nextPID++;
		runningProcesses++;
		sharedStateLock.release();

		// stdin/stdout
		fileTable[0] = UserKernel.console.openForReading();
		FileRef.referenceFile(fileTable[0].getName());
		fileTable[1] = UserKernel.console.openForWriting();
		FileRef.referenceFile(fileTable[1].getName());

		// Exit/Join syncronization
		waitingToJoin = new Condition(joinLock);
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;
		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Check to see if the virtual address is valid.
	 * @param vaddr
	 * @return true if the virtual address is valid
	 */
	protected boolean validAddress(int vaddr) {
		int vpn = Processor.pageFromAddress(vaddr);
		return vpn < numPages && vpn >= 0;
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr
	 *            the starting virtual address of the null-terminated string.
	 * @param maxLength
	 *            the maximum number of characters in the string, not including
	 *            the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 *         found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @param offset
	 *            the first byte to write in the array.
	 * @param length
	 *            the number of bytes to transfer from virtual memory to the
	 *            array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length && memoryAccessLock != null);
		if (!validAddress(vaddr)) {
			return 0;
		} else {
			Collection<MemoryAccess> memoryAccesses = createMemoryAccesses(vaddr, data, offset, length, AccessType.READ);
			
			int bytesRead = 0, temp;
			
			memoryAccessLock.acquire();
			for (MemoryAccess ma : memoryAccesses) {
				temp = ma.executeAccess();
				
				if (temp == 0)
					break;
				else
					bytesRead += temp;
			}
			memoryAccessLock.release();
			return bytesRead;
		}
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @param offset
	 *            the first byte to transfer from the array.
	 * @param length
	 *            the number of bytes to transfer from the array to virtual
	 *            memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length && memoryAccessLock != null);
		if (!validAddress(vaddr)) {
			return 0;
		} else {
			Collection<MemoryAccess> memoryAccesses = createMemoryAccesses(vaddr, data, offset, length, AccessType.WRITE);
			
			int bytesWritten = 0, temp;
			memoryAccessLock.acquire();
			for (MemoryAccess ma : memoryAccesses) {
				temp = ma.executeAccess();
				if (temp == 0)
					break;
				else
					bytesWritten += temp;
			}
			memoryAccessLock.release();
			
			return bytesWritten;
		}
	}
	
	/**
	 * Generates a set of <tt>MemoryAccess</tt> instances corresponding to the desired action.
	 * @param vaddr
	 * @param data
	 * @param offset
	 * @param length
	 * @param accessType
	 * @author Sam Whitlock (cs162-ap)
	 * @return A collection of <tt>MemoryAccess</tt> instances corresponding to the desired action.
	 */
	private Collection<MemoryAccess> createMemoryAccesses(int vaddr, byte[] data, int offset, int length, AccessType accessType) {
		LinkedList<MemoryAccess> returnList = new LinkedList<MemoryAccess>();
		
		while (length > 0) {
			int vpn = Processor.pageFromAddress(vaddr);
			
			int potentialPageAccess = Processor.pageSize - Processor.offsetFromAddress(vaddr);
			int accessSize = length < potentialPageAccess ? length : potentialPageAccess;
			
			returnList.add(new MemoryAccess(accessType, data, vpn, offset, Processor.offsetFromAddress(vaddr), accessSize));
			length -= accessSize;
			vaddr += accessSize;
			offset += accessSize;
		}
		
		return returnList;
	}

	/**
	 * An inner class to represent a memory access.
	 * @author Sam Whitlock (cs162-ap)
	 */
	protected class MemoryAccess {
		protected MemoryAccess(AccessType at, byte[] d, int _vpn, int dStart, int pStart, int len) {
			accessType = at;
			data = d;
			vpn = _vpn;
			dataStart = dStart;
			pageStart = pStart;
			length = len;
		}
		
		/**
		 * Execute the requested memory access.
		 * @return The number of bytes successfully written (or 0 if it fails).
		 */
		public int executeAccess() {
			if (translationEntry == null)
				translationEntry = pageTable[vpn];
			if (translationEntry.valid) {
				if (accessType == AccessType.READ) {//Do a read
					System.arraycopy(Machine.processor().getMemory(), pageStart + (Processor.pageSize * translationEntry.ppn), data, dataStart, length);
					translationEntry.used = true;
					return length;
				} else if (!translationEntry.readOnly && accessType == AccessType.WRITE) {//FIXME: If this last part necessary?
					System.arraycopy(data, dataStart, Machine.processor().getMemory(), pageStart + (Processor.pageSize * translationEntry.ppn), length);
					translationEntry.used = translationEntry.dirty = true;
					return length;
				}
			}
			
			return 0;
		}
		
		/**
		 * A reference to the data array we are supposed to fill or write from
		 */
		protected byte[] data;
		
		/**
		 * Which access should occur
		 */
		protected AccessType accessType;
		
		/**
		 * The translation entry corresponding to the appropriate page to be accessed.
		 */
		protected TranslationEntry translationEntry;
		
		/**
		 * Bounds for accessing the data array.
		 */
		protected int dataStart;
		
		/**
		 * Bounds for accessing the page.
		 */
		protected int pageStart;

		/**
		 * Length of the access (the same for the array and the page).
		 */
		protected int length;
		
		/**
		 * The VPN of the page needed.
		 */
		protected int vpn;
	}
	
	/**
	 * An enum to represent what data access should be done
	 */
	protected static enum AccessType {
		READ, WRITE
	};

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	protected boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		} catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		loadArguments(entryOffset, stringOffset, argv);

		return true;
	}

	/**
	 * Loads the arguments into the last page
	 */
	protected void loadArguments(int entryOffset, int stringOffset, byte[][] argv) {
		//load the arguments into the last page
		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}
	}
	
	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		try {
			pageTable = ((UserKernel) Kernel.kernel).acquirePages(numPages);
			
			for (int i = 0; i < pageTable.length; i++)
				pageTable[i].vpn = i;
			
			for (int sectionNumber = 0; sectionNumber < coff.getNumSections(); sectionNumber++) {
				CoffSection section = coff.getSection(sectionNumber);

				Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

				int firstVPN = section.getFirstVPN();
				for (int i = 0; i < section.getLength(); i++)
					section.loadPage(i, pageTable[i+firstVPN].ppn);
			}
		} catch (InadequatePagesException a) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		} catch (ClassCastException c) {
			Lib.assertNotReached("Error : instantiating a UserProcess without a UserKernel");
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		try {
			((UserKernel)Kernel.kernel).releasePages(pageTable);
		} catch (ClassCastException c) {
			Lib.assertNotReached("Error : Kernel is not an instance of UserKernel");
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < Processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		// halt() is noop if not root process
		if (PID != 0)
			return 0;

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	/**
	 * Return first unused file descriptor, or -1 if fileTable full
	 */
	protected int getFileDescriptor() {
		for (int i = 0; i < fileTable.length; i++) {
			if (fileTable[i] == null)
				return i;
		}
		return -1;
	}

	/**
	 * Return whether the given file descriptor is valid
	 */
	private boolean validFileDescriptor(int fileDesc) {
		// In range?
		if (fileDesc < 0 || fileDesc >= fileTable.length)
			return false;
		// Table entry valid?
		return fileTable[fileDesc] != null;
	}

	/**
	 * Handle creat(char* filename) system call
	 * @param fileNamePtr
	 * 		pointer to null terminated file name
	 * @return
	 * 		file descriptor used to further reference the new file
	 */
	private int handleCreate(int fileNamePtr) {
		return openFile(fileNamePtr, true);
	}

	/**
	 * Handle open(char* filename) system call
	 * @param fileNamePtr
	 * 		pointer to null terminated file name
	 * @return
	 * 		file descriptor used to further reference the new file
	 */
	private int handleOpen(int fileNamePtr) {
		return openFile(fileNamePtr, false);
	}

	/**
	 * Open a file and add it to the process file table
	 */
	private int openFile(int fileNamePtr, boolean create) {
		if (!validAddress(fileNamePtr))
			return terminate();

		// Try to get an entry in the file table
		int fileDesc = getFileDescriptor();
		if (fileDesc == -1)
			return -1;

		String fileName = readVirtualMemoryString(fileNamePtr, maxSyscallArgLength);

		// Attempt to add a new reference to this file
		if (!FileRef.referenceFile(fileName))
			return -1;	// Cannot make new references to files that are marked for deletion

		// Attempt to actually open the file
		OpenFile file = UserKernel.fileSystem.open(fileName, create);
		if (file == null) {
			// Remove the previously created reference since we failed to open the file
			FileRef.unreferenceFile(fileName);
			return -1;
		}

		// Store the file in our file table
		fileTable[fileDesc] = file;

		return fileDesc;
	}

	/**
	 * Read data from open file into buffer
	 * @param fileDesc
	 * 		File descriptor
	 * @param bufferPtr
	 * 		Pointer to buffer in virtual memory
	 * @param size
	 * 		How much to read
	 * @return
	 * 		Number of bytes read, or -1 on error
	 */
	private int handleRead(int fileDesc, int bufferPtr, int size) {
		if (!validAddress(bufferPtr))
			return terminate();
		if (!validFileDescriptor(fileDesc))
			return -1;

		byte buffer[] = new byte[size];
		int bytesRead = fileTable[fileDesc].read(buffer, 0, size);

		// Failed to read
		if (bytesRead == -1)
			return -1;

		int bytesWritten = writeVirtualMemory(bufferPtr, buffer, 0, bytesRead);
		// We weren't able to write the whole buffer to memory!
		if (bytesWritten != bytesRead)
			return -1;

		return bytesRead;
	}

	/**
	 * Write data from buffer into an open file
	 * @param fileDesc
	 * 		File descriptor
	 * @param bufferPtr
	 * 		Pointer to buffer in virtual memory
	 * @param size
	 * 		Size of buffer
	 * @return
	 * 		Number of bytes successfully written, or -1 on error
	 */
	private int handleWrite(int fileDesc, int bufferPtr, int size) {
		if (!validAddress(bufferPtr))
			return terminate();
		if (!validFileDescriptor(fileDesc))
			return -1;

		byte buffer[] = new byte[size];
		int bytesRead = readVirtualMemory(bufferPtr, buffer);
		int bytesWritten = fileTable[fileDesc].write(buffer, 0, bytesRead);

		// -1 if error
		return bytesWritten;
	}

	/**
	 * Close a file and free its place in the file table
	 * @param fileDesc
	 * 		Index of file in file table
	 * @return
	 * 		0 on success, -1 on error
	 */
	private int handleClose(int fileDesc) {
		if (!validFileDescriptor(fileDesc))
			return -1;

		String fileName = fileTable[fileDesc].getName();

		// Remove the file from our file table
		fileTable[fileDesc].close();
		fileTable[fileDesc] = null;

		// Unreference the file and delete if necessary
		return FileRef.unreferenceFile(fileName);
	}

	/**
	 * Mark a file as pending deletion, and remove it if there are no currently active references
	 * If not immediately removed, the file will be removed when all the active references are closed
	 * @param fileNamePtr
	 * 		Pointer to null terminated string with filename
	 * @return
	 * 		0 on success, -1 on error
	 */
	private int handleUnlink(int fileNamePtr) {
		if (!validAddress(fileNamePtr))
			return terminate();

		String fileName = readVirtualMemoryString(fileNamePtr, maxSyscallArgLength);
		return FileRef.deleteFile(fileName);
	}

	/**
	 * Handle spawning a new process
	 * @param fileNamePtr
	 * 		Pointer to null terminated string containing executable name
	 * @param argc
	 * 		Number of arguments to pass new process
	 * @param argvPtr
	 * 		Array of null terminated strings containing arguments
	 * @return
	 * 		PID of child process, or -1 on failure
	 */
	private int handleExec(int fileNamePtr, int argc, int argvPtr) {
		// Verify that passed pointers are valid
		if (!validAddress(fileNamePtr) || !validAddress(argv))
			return terminate();

		// Read filename from virtual memory
		String fileName = readVirtualMemoryString(fileNamePtr, maxSyscallArgLength);
		if (fileName == null || !fileName.endsWith(".coff"))
			return -1;

		// Gather arguments for the new process
		String arguments[] = new String[argc];

		// Read the argv char* array
		int argvLen = argc * 4;	// Number of bytes in the array
		byte argvArray[] = new byte[argvLen];
		if (argvLen != readVirtualMemory(argvPtr, argvArray)) {
			// Failed to read the whole array
			return -1;
		}

		// Read each argument string from the char* array
		for (int i = 0; i < argc; i++) {
			// Get char* pointer for next position in array
			int pointer = Lib.bytesToInt(argvArray, i*4);

			// Verify that it is valid
			if (!validAddress(pointer))
				return -1;

			// Read in the argument string
			arguments[i] = readVirtualMemoryString(pointer, maxSyscallArgLength);
		}

		// New process
		UserProcess newChild = newUserProcess();
		newChild.parent = this;

		// Remember our children
		children.put(newChild.PID, new ChildProcess(newChild));

		// Run and be free!
		newChild.execute(fileName, arguments);

		return newChild.PID;
	}

	/**
	 * Handle exiting and cleanup of a process
	 * @param status
	 * 		Integer exit status, or null if exiting due to unhandled exception
	 * @return
	 * 		Irrelevant - user process never sees this syscall return
	 */
	private int handleExit(Integer status) {
		joinLock.acquire();

		// Attempt to inform our parent that we're exiting
		if (parent != null)
			parent.notifyChildExitStatus(PID, status);

		// Disown all of our running children
		for (ChildProcess child : children.values())
			if (child.process != null)
				child.process.disown();
		children = null;

		// Loop through all open files and close them, releasing references
		for (int fileDesc = 0; fileDesc < fileTable.length; fileDesc++)
			if (validFileDescriptor(fileDesc))
				handleClose(fileDesc);

		// Free virtual memory
		unloadSections();

		// Wakeup anyone who is waiting for us to exit
		exited = true;
		waitingToJoin.wakeAll();
		joinLock.release();

		// Halt the machine if we were the last process
		sharedStateLock.acquire();
		if (--runningProcesses == 0)
			Kernel.kernel.terminate();
		sharedStateLock.release();

		// Terminate current thread
		KThread.finish();

		return 0;
	}

	/**
	 * Called on a parent process by an exiting child to inform them that the child has terminated. 
	 * @param childPID
	 * @param childStatus
	 * 		Value of the exit status, or null if exited due to unhandled exception
	 */
	protected void notifyChildExitStatus(int childPID, Integer childStatus) {
		ChildProcess child = children.get(childPID);
		if (child == null)
			return;

		// Remove reference to actual child so it can be garbage collected
		child.process = null;
		// Record child's exit status for posterity
		child.returnValue = childStatus;
	}

	/**
	 * Called on a child by an exiting parent to inform them that they are now an orphan.
	 */
	protected void disown() {
		parent = null;
	}

	/**
	 * Terminate this process due to unhandled exception
	 */
	private int terminate() {
		handleExit(null);
		return -1;
	}

	/**
	 * Wait for child process to exit and transfer exit value
	 * @param pid
	 * 		Pid of process to join on
	 * @param statusPtr
	 * 		Pointer to store process exit status
	 * @return
	 * 		-1 on attempt to join non child process
	 * 		1 if child exited due to unhandled exception
	 * 		0 if child exited cleanly
	 */
	private int handleJoin(int pid, int statusPtr) {
		if (!validAddress(statusPtr))
			return terminate();

		ChildProcess child = children.get(pid);

		// Can't join on non-child!
		if (child == null)
			return -1;

		// Child still running, try to join
		if (child.process != null)
			child.process.joinProcess();
		// We can safely forget about this child after join
		children.remove(pid);

		// Child will have transfered return value to us

		// Child exited due to unhandled exception
		if (child.returnValue == null)
			return 0;

		// Transfer return value into status ptr
		writeVirtualMemory(statusPtr, Lib.bytesFromInt(child.returnValue));

		// Child exited cleanly
		return 1;
	}

	/**
	 * Cause caller to sleep until this process has exited
	 */
	private void joinProcess() {
		joinLock.acquire();
		while (!exited)
			waitingToJoin.sleep();
		joinLock.release();
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
	syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
	syscallRead = 6, syscallWrite = 7, syscallClose = 8,
	syscallUnlink = 9;

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
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
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
		case syscallHalt:
			return handleHalt();

		case syscallExit:
			return handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);

		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);

		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exception</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: " + Processor.exceptionNames[cause]);

			// Process did something naughty
			terminate();

			Lib.assertNotReached("Unexpected exception");
		}
	}

	/**
	 * Internal class to keep track of children processes and their exit value
	 */
	private static class ChildProcess {
		public Integer returnValue;
		public UserProcess process;

		ChildProcess(UserProcess child) {
			process = child;
			returnValue = null;
		}
	}

	/**
	 * Internal class to keep track of how many processes reference a given file
	 */
	protected static class FileRef {
		int references;
		boolean delete;

		/**
		 * Increment the number of active references there are to a file
		 * @return
		 * 		False if the file has been marked for deletion
		 */
		public static boolean referenceFile(String fileName) {
			FileRef ref = updateFileReference(fileName);
			boolean canReference = !ref.delete;
			if (canReference)
				ref.references++;
			finishUpdateFileReference();
			return canReference;
		}

		/**
		 * Decrement the number of active references there are to a file
		 * Delete the file if necessary
		 * @return
		 * 		0 on success, -1 on failure
		 */
		public static int unreferenceFile(String fileName) {
			FileRef ref = updateFileReference(fileName);
			ref.references--;
			Lib.assertTrue(ref.references >= 0);
			int ret = removeIfNecessary(fileName, ref);
			finishUpdateFileReference();
			return ret;
		}

		/**
		 * Mark a file as pending deletion, and delete the file if no active references
		 * @return
		 * 		0 on success, -1 on failure
		 */
		public static int deleteFile(String fileName) {
			FileRef ref = updateFileReference(fileName);
			ref.delete = true;
			int ret = removeIfNecessary(fileName, ref);
			finishUpdateFileReference();
			return ret;
		}

		/**
		 * Remove a file if marked for deletion and has no active references
		 * Remove the file from the reference table if no active references
		 * THIS FUNCTION MUST BE CALLED WITHIN AN UPDATEFILEREFERENCE LOCK!
		 * @return
		 * 		0 on success, -1 on failure to remove file
		 */
		private static int removeIfNecessary(String fileName, FileRef ref) {
			if (ref.references <= 0) {
				globalFileReferences.remove(fileName);
				if (ref.delete == true) {
					if (!UserKernel.fileSystem.remove(fileName))
						return -1;
				}
			}
			return 0;
		}

		/**
		 * Lock the global file reference table and return a file reference for modification.
		 * If the reference doesn't already exist, create it.
		 * finishUpdateFileReference() must be called to unlock the table again!
		 *
		 * @param fileName
		 * 		File we with to reference
		 * @return
		 * 		FileRef object
		 */
		private static FileRef updateFileReference(String fileName) {
			globalFileReferencesLock.acquire();
			FileRef ref = globalFileReferences.get(fileName);
			if (ref == null) {
				ref = new FileRef();
				globalFileReferences.put(fileName, ref);
			}

			return ref;
		}

		/**
		 * Release the lock on the global file reference table
		 */
		private static void finishUpdateFileReference() {
			globalFileReferencesLock.release();
		}

		/** Global file reference tracker & lock */
		private static HashMap<String, FileRef> globalFileReferences = new HashMap<String, FileRef> ();
		private static Lock globalFileReferencesLock = new Lock();
	}

	/** Lock to protect static variables */
	private static Lock sharedStateLock = new Lock();

	/** Process ID */
	private static int nextPID = 0;
	protected int PID;

	/** Parent/Child process tree */
	protected UserProcess parent;
	private HashMap<Integer, ChildProcess> children = new HashMap<Integer, ChildProcess> ();

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';

	/**
	 * A lock to protect memory accesses.
	 */
	private Lock memoryAccessLock = new Lock();

	/** Process file descriptor table */
	protected OpenFile[] fileTable = new OpenFile[16];

	/** Join condition */
	private boolean exited = false;
	private Lock joinLock = new Lock();
	private Condition waitingToJoin;

	/** Number of processes */
	private static int runningProcesses = 0;

	/** The maximum length of any system call string argument */
	private static final int maxSyscallArgLength = 256;
}
