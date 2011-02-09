#include "syscall.h"
#include "stdio.h"

//Read only a portion of the message to ensure that all data packets are acked
//This test will succeed if st1.c also shuts down (i.e. it isn't waiting for data ack for the packets)

int main(int argc, char* argv[]) {
	char msg[] = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
	int host, socket, bytes, totalBytes = 0, i;
	host = atoi(argv[0]);
	socket = connect(host,2);

	for (i = 0; i < 21; i++) {
		bytes = write(socket, msg, 1000);
		totalBytes += bytes;

		if (bytes == -1) {
			printf("Error : Expected to write %d bytes, but wrote %d\n", sizeof(msg)-1 * 20, totalBytes);
			return 1;
		}
	}

	printf("Send %d total bytes\n", totalBytes);

	bytes = close(socket);

	if (bytes == -1) {
		printf("Error when closing socket\n");
		return 1;
	}

 	return 0;//Success
}
