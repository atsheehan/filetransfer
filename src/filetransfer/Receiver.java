package filetransfer;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.SocketException;


public class Receiver {
	
    private int listeningPort;
    private String lastError;

    private final int THREAD_TIMEOUT = 10000;

    public Receiver(String [] args) {

	// Validate the user input before continuing.
	if (!parseArgs(args)) {
	    System.exit(1);
	}

	// Create a new thread to listen on the specified socket.
	FileReceiveBuffer fileReceiver = null;
	try {
	    fileReceiver = new FileReceiveBuffer(listeningPort);
	} catch (SocketException e) {
	    System.err.println("[error] could not open socket. message: " + e.getMessage());
	    System.exit(1);
	}
	fileReceiver.start();

	BufferedOutputStream writer = null;
	AckSender ackSender = null;
	boolean initialized = false;

	while (true) {

	    // Get the next sequential packet from the FileReceiveBuffer. If the next
	    // packet hasn't arrived yet, this call with block until it becomes available.
	    DataPacket packet = fileReceiver.getNextPacket();

	    // If the transfer has not already been initialized,
	    // wait for the init packet before we start writing
	    // anything to disk.
	    if (!initialized) {
		if (!packet.isInitPacket()) {
		    continue;
		}

		// The initialization packet should contain the name of the file that we are 
		// going to write.
		try {
		    writer = new BufferedOutputStream(new FileOutputStream(packet.getFilename()));
		} catch (FileNotFoundException e) {
		    System.err.println("[error] could not create new file. message: " + e.getMessage());
		    fileReceiver.close();
		    System.exit(1);
		} 

		initialized = true;
		continue;
	    }

	    // Ignore any other init packets from now on.
	    if (packet.isInitPacket()) {
		continue;
	    }

	    // The last packet flag will indicate that we have received all
	    // of the file.
	    if (packet.isLastPacket()) {
		fileReceiver.stopListening();
		break;
	    }

	    // Write the chunk of data from the packet to disk. If there is an error writing
	    // to disk, stop listening for packets and break out of the loop.
	    try {
	    	writer.write(packet.getData());
	    } catch (IOException e) {
		System.err.println("[error] could not write to file. message: " + e.getMessage());
		fileReceiver.stopListening();
		break;
	    }
	}

	// Send 10 duplicate ACK packets when finished and hope that not all 10 are lost/mangled.
	fileReceiver.sendLastAck(10);
	
	try {
		writer.close();
	} catch (IOException e) {
		System.err.println("[error] file writer failed to close: " + e.getMessage());
	} finally {
		fileReceiver.close();
	}

	// Wait for the thread to complete before exiting.
	try {
	    fileReceiver.join(THREAD_TIMEOUT);
	} catch (InterruptedException e) {
	    System.err.println("[error] interrupted while closing threads. " + 
			       "transfer may not have finished normally.");
	}

	System.err.println("[completed]");
    }

    private boolean parseArgs(String[] args) {

	listeningPort = -1;
	int argc = args.length;

	if (argc != 2) {
	    System.err.println("usage: recvfile -p <recv_port>");
	    return false;
	}

	for (int i = 0; i < argc; i++){
			
	    if (args[i].equals("-p") && i + 1 < argc) {

		try {
		    listeningPort = Integer.parseInt(args[i + 1]);
		} catch (NumberFormatException e) {
		    System.err.println("[error] <recv_port> must be an integer");
		    return false;
		}
	    }
	}

	if (listeningPort < 0) {
	    System.err.println("[error] <recv_port> must be a positive integer");
	    return false;
	}
	
	return true;
    }

    public static void main(String[] args) {
	new Receiver(args);
    }
}	