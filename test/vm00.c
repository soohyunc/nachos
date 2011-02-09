#include "stdio.h"
#include "stdlib.h"
#include "syscall.h"

char test[65536];

//Test should be run with 2 physical pages.
//Test 1 read
int main(void)
{
	int index, bad = 0;
	
	test[0] = 29;
	test[1] = 42;
	test[2] = 33;
	test[3] = 99;
	for (index = 1; index < 63; index++) { 
		test[index*1024] = index;
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
