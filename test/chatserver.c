#include "syscall.h"
#include "stdio.h"

#define MAX_TEXT_SIZE 1000
#define MAX_CLIENT_SOCKETS 16

int clientSockets[MAX_CLIENT_SOCKETS], receivedEnd;
char receivedText[MAX_TEXT_SIZE];

void broadcastFromClient(int clientNum);

int main(int argc, char* argv[]) {
	int newSocket = 0, i;
	char result[1];

	for (i = 0; i < MAX_CLIENT_SOCKETS; i++) { // init clientSockets
		clientSockets[i] = -1;
	}

	while (1) {
		if (read(stdin, result, 1) != 0) {
			break; // Input character so exit.
		}

		newSocket = accept(15); // Check for new socket
		if (newSocket != -1) { // If new socket, add to client server list
            printf("client %d connected\n", newSocket);
			clientSockets[newSocket] = newSocket;
		}
		for (i = 0; i < MAX_CLIENT_SOCKETS; i++) {
			if (clientSockets[i] != -1) {
				broadcastFromClient(i);
			}
		}
	}
	
	// We don't need to call close on our sockets. It is done explicitly upon kernel termination.
}

// broadcasts next line from a specified client to all the clients
void broadcastFromClient(int clientNum) {
	int i, bytesWrit, bytesRead;
	char result[1];
    
	// Check for char from client
	bytesRead = read(clientSockets[clientNum], result, 1);

	// If client disconnected, kill it
	if (bytesRead == -1) {
        printf("disconnecting client %d\n", clientNum);
        close(clientSockets[clientNum]);
        clientSockets[clientNum] = -1;
        return;
    }
    // Abort if no text received
	if (bytesRead == 0) return;
	
	receivedEnd = 0;
	// Else get all chars from client until next '/n'
	while ((bytesRead > -1) && (receivedEnd < MAX_TEXT_SIZE)) {
		receivedText[receivedEnd++] = result[0];
		if (result[0] == '\n') break;
		bytesRead = read(clientSockets[clientNum], result, 1);
	}
	
    // Abort if no text received
	if (receivedEnd == 0) return;
	
    receivedText[receivedEnd] = '\0';
    printf("broadcast: %s",receivedText);
    
	// If there was any text received from the client, broadcast that line to each client
	for (i = 0; i < MAX_CLIENT_SOCKETS; ++i)
		if (i != clientNum && clientSockets[i] != -1) { // Do not broadcast to client it came from
			bytesWrit = write(clientSockets[i], receivedText, receivedEnd);

			// If did not work (bytesWrit != receivedEnd) disconnect client
			if (bytesWrit != receivedEnd) {
				printf("Unable to write to client %d. Disconnecting client.", i);
				close(clientSockets[i]);
				clientSockets[i] = -1;
            }
		}
}
