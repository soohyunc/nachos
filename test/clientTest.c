#include "syscall.h"
#include "stdio.h"

char buf[128];

int main(int argc, char* argv[]) {
	int host, socket, bytes;
	host = atoi(argv[0]);
	buf[499] = '\0';
	socket = connect(host,2);

	while ((bytes = read(socket,buf,50)) != -1) {
		buf[bytes] = '\0';
		printf("%s",buf);
	}


 	return 0;//Success
}
