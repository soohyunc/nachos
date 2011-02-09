#include "syscall.h"
#include "stdio.h"

#define MAX_TEXT_SIZE 1000
#define false 0
#define true 1

char receivedText[MAX_TEXT_SIZE], sendText[MAX_TEXT_SIZE];
int  receivedEnd, sendEnd, host, socket, bytesRead, bytesWrit;

// Connects to the server specified in the first argument.
// Receives line input from stdIn, sending it to socket (the server).
// Receives lines from the server and puts them to stdOut.
int main(int argc, char* argv[]) {
	int host, socket, bytesRead, bytesWrit, done = false;
	char lastByte;
	receivedEnd = 0;

	if (argc != 2) {
        printf("error: please supply host address\n");
        return 1;
    }

	host = atoi(argv[1]);
	socket = connect(host, 15);

	printf("Successfully connected to host %d\n", host);
	// Loop endlessly until there is a single '.'
	while(!done) {
		sendEnd = 0; // reset length of sendText

		//Try to read the first byte of stdin
		if ((bytesRead = read(stdin, sendText, 1)) == 1) { // block until '\n'
			//Hooray we found something!
			lastByte = sendText[0];
			sendEnd++;
			while (lastByte != '\n') {
				if ((bytesRead = read(stdin, sendText + sendEnd, 1)) == -1) {
					printf("Error : Can't read from stdin. Bye!\n");
					done = true;
					break;
				} else {
					// Record the concatenation
					sendEnd += bytesRead;
					lastByte = sendText[sendEnd - 1];

					// Stop getting input if sendEnd == MAX_TEXT_SIZE - 1
					if (sendEnd == MAX_TEXT_SIZE - 1) {
						sendText[MAX_TEXT_SIZE - 1] = '\n';
						break;
					}
				}
			}

			// Break if we received the termination sequence
			if (sendText[0] == '.' && sendText[1] == '\n') {
				printf("Received exit command. Bye!\n");
				break;
			} else if(sendText[0] != '\n') {// Send to server if significant message
				bytesWrit = write(socket, sendText, sendEnd);

				if (bytesWrit == -1) {// If we can't send, server has terminated
					printf("Server not responding. Bye!\n");
					break;
				}
			}
		}

		// read from socket (chat server)
		bytesRead = read(socket, receivedText + receivedEnd, 1);
		if (bytesRead == 1) { // If there is a char from the socket, read it
			lastByte = receivedText[receivedEnd++];
			if (lastByte == '\n') {  // if it is a new line, write out the line to the stdOut
				bytesWrit = write(stdout, receivedText, receivedEnd);
				// Reset the receivedText string for more input from socket
				receivedEnd = 0;
			}
		} else if (bytesRead == -1) {// Unexpected remote termination
			printf("Server shutdown. Bye!\n");
			break;
		}
	}

	close(socket);

	return 0;//Success
}
