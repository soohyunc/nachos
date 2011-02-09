#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

#define BUFSIZE 1024
#define BIG 4096

char buf[BUFSIZE], buf2[BUFSIZE], buf3[BUFSIZE], bigBuf[BIG], bigBuf1[BIG];

char *cpargv[20]= {"me2.txt","me2copy.txt"}; //Todo (FC): Make syntax correct


int main(void)
{
	//Test to see that file descriptors can be successfully reused
	//Also tests creat, unlink, and close
    int i, fileDescr, status, stdClose;
    for (i = 0; i < 17; i++) {
        fileDescr = creat("me.txt");
        if (fileDescr == -1) {
            printf("Error: bad file descriptor on iteration %d", i);
            return 1;
        }
        close(fileDescr);
        unlink("me.txt");
    }
	//test reads and writes to a file
	fileDescr = creat("me2.txt");
	if (fileDescr == -1) {
		printf("Error: could not make a file");
		return 1;
	}

	for (i = 0; i < 26; i++) {
		buf[i] = 'a' + i;
	}

	status = write(fileDescr,buf,26);

	if (status == -1) {
		printf("Error: could not write a file");
		return 1;
	}

	//read the data back
	status = read(fileDescr, buf2, 26);

	if (status == -1) {
		printf("Error: unable to read data back from a file");
		return 1;
	}

	//close the file, and verify that we read back the correct data
	status = close(fileDescr);
	
	if (status == -1) {
		printf("Error: unable to close the file");
		return 1;
	}

    //FC. COFF Check that exec is paging code properly
	exec("../test/cp", 2, cpargv);

	//FC.reopen the file's copy
    fileDescr = open("me2copy.txt");
    if (fileDescr == -1) {
        printf("Error: unable to reopen the file me2copy.txt. Exec('cp') did not work");
        return 1;
    }
   
    status = read(fileDescr,buf3,26);

    if (status == -1) {
        printf("Error: unable to reread the file me2copy.txt");
        return 1;
    }

    //FC.verify that me2copy has the correct data
    for (i=0; i < 26; i++) {
        if (buf3[i] != 'a' + i) {
            printf("Error: bad value reread back to me2copy.txt. Exec('cp') did not work.");
            return 1;
        }
    }

	//FC.close the file and test that it stays closed
	status = unlink("me2copy.txt");//mark it for deletion

	//reopen the file
	fileDescr = open("me2.txt");
	if (fileDescr == -1) {
		printf("Error: unable to reopen the file");
		return 1;
	}
	
	status = read(fileDescr,buf3,26);

	if (status == -1) {
		printf("Error: unable to reread the file");
		return 1;
	}

	//verify that it read the correct data
	for (i=0; i < 26; i++) {
		if (buf3[i] != 'a' + i) {
			printf("Error: bad value reread back");
			return 1;
		}
	}

	//close the file and test that it stays closed
	status = unlink("me2.txt");//mark it for deletion

	if (status == -1) {
		printf("Error: could not unlink me2.txt");
		return 1;
	}
	//test that it is not yet deleted
	status = read(fileDescr,buf2,26);
	
	if (status == -1) {
		printf("Error: unlink deleted a file early while others still accessing");
		return 1;
	}

	//now actually delete the file by closing it
	status = close(fileDescr);
	
	if (status == -1) {
		printf("Error: tried to close file while it was last one with it open");
		return 1;
	}

	//it should now be closed, so open should fail
	fileDescr = open("me2.txt");//this should not create the file

	if (fileDescr != -1) {
		printf("Error: open syscall created a file we deleted");
		return 1;
	}

	//Testing big reads and writes to see if they fail or not
	//Initialize data first
	for (i = 0; i < BIG; i++)
		bigBuf[i] = 'a' + (i^2);

	//open a file and write this randomness to it
	fileDescr = creat("bigFileTest.txt");
	if (fileDescr == -1) {
		printf("Error: unable to open file for big io test");
		return 1;
	}

	status = write(fileDescr,bigBuf, BIG);

	if (status == -1) {
		printf("Error: unable to write big data");
		return 1;
	}
      
	//read the data back and verify
	close(fileDescr);
	fileDescr = open("bigFileTest.txt");
	if (fileDescr == -1) {
	  printf("Error: unable to reopen file for big io test");
	}

	status = read(fileDescr,bigBuf1, BIG);

	if (status == -1) {
		printf("Error: unable to read back big data");
		return 1;
	}

	for (i = 0; i < BIG; i++) {
		if (bigBuf[i] != bigBuf1[i]) {
			printf("Error: did not read back the expected data");
			return 1;
		}
	}

	unlink("bigFileTest.txt");
	close(fileDescr);	

	printf("Tests successful!");
	//Test to see that stdin and stdout are able to close successfully
	//These must go at the end, probably
	stdClose = close(0);
	if (stdClose == -1) {
		printf("Error: could not close stdin");
		return 1;
	}

	stdClose = close(1);
	if (stdClose == -1) {
		printf("Error: could not close stdout");
		return 1;
	}

	printf("Success: All Tests Pass | Huzzahs all around!");

    return 0;
}
