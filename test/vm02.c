#include "stdio.h"
#include "stdlib.h"
#include "syscall.h"

char test[65536];

//Test c
int main(void)
{
	int index, bad = 0, filedes, bytesread, byteswrit;
	test[0] = 29;
	test[1] = 42;
	test[2] = 33;
	test[3] = 99;
	for (index = 1; index < 63; index++) { 
		test[index*1024] = index;
	}
	filedes = creat("hello.txt");
	byteswrit = write(filedes, test, 4);
	close(filedes);
	filedes = open("hello.txt")
	bytesread = read(filedes, test + 1025, 4);
	if (test[1025] != 29) {
		printf("Error: test[1025] should be 29");
		bad++;
	}
	if (test[1026] != 42) {
		printf("Error: test[1026] should be 42");
		bad++;
	}
	
	if (test[1027] != 33) {
		printf("Error: test[1027] should be 33");
		bad++;
	}
	
	if (test[1028] != 99) {
		printf("Error: test[1028] should be 99");
		bad++;
	}
		
	if (test[0] != 29) {
		printf("Error: test[0] should be 29");
		bad++;
	}
	
	if (test[1] != 42) {
		printf("Error: test[1] should be 42");
		bad++;
	}
	
	if (test[2] != 33) {
		printf("Error: test[2] should be 33");
		bad++;
	}
	
	if (test[3] != 99) {
		printf("Error: test[3] should be 99");
		bad++;
	}
	
	
	if (bad > 0) {
		exit(1);
	} else {
		exit(0);
	}
}
