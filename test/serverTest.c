#include "syscall.h"
#include "stdio.h"

int main(int argc, char* argv[]) {
	int connection, bytes;\
	char msg[] = "this is a long message that will require being fragmented into multiple packets over the NTP protocol. Yay. I sure hope it works.";
	do {
		connection = accept(2);
	} while(connection == -1);

	bytes = write(connection, msg, sizeof(msg));

	if (bytes == -1) {
		printf("Error : Expected to write all bytes, but wrote -1");
		return 1;
	}

	return 0;
}
