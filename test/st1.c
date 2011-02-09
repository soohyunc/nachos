#include "syscall.h"
#include "stdio.h"

char buf[1024];

int main(int argc, char* argv[]) {
	char msg[] = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
	int socket, bytes, totalBytes = 0;

	do {
		socket = accept(2);
	} while(socket == -1);

	while ((bytes = read(socket,buf,1000)) > -1) {
			buf[bytes] = '!';
			buf[bytes+1] = '\0';
			printf("read %d bytes: %s\n", bytes, buf);
			totalBytes += bytes;
		}

		printf("Read %d total bytes\n", totalBytes);

		if (close(socket) == -1) {
			printf("Error : Received -1 on socket closing");
			return 1;
		}

	return 0;
}
