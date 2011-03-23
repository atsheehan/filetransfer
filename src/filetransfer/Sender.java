package filetransfer;

import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;

public class Sender {
	
    private int sendingPort;
    private InetAddress destination;
    private File file;
    private AckReceiver ackReceiver;
    private FileSendBuffer sender;

    static public final int	SEGMENT_SIZE   = 1000;
    static final int		EOF	       = -1;
    static final long		ACK_TIMEOUT    = 30000; 
    static final long		THREAD_TIMEOUT = 1000;
	
    public Sender(String [] args) {

	// Record the starting time so we can calculate how long the transfer took.
	long startTime = new Date().getTime();

	// Validate the user input before continuing.
	if (!parseArgs(args)) {
	    System.exit(1);
	}

	// Before sending any data over the network, verify that the file we are trying to send is
	// valid by attempting to open it.
	BufferedInputStream reader = null;
	try {
	    reader = new BufferedInputStream(new FileInputStream(file));
	} catch (FileNotFoundException e) {
	    System.err.println("[error] could not open the specified file. message: " + e.getMessage());
	    System.exit(1);
	}

	// Create a new thread to listen for incoming ACK packets.
	try {
	    ackReceiver = new AckReceiver();
	} catch (SocketException e) {
	    try{
		System.err.println("[error] could not create the ack receiver. message: " + e.getMessage());
		reader.close();
		sender.close();
	    } catch (IOException r) {
		System.err.println("[error] file reader failed to close: " + r.getMessage());
	    } finally {
		System.exit(1);  	
	    }
	}

	// Try opening a socket to send packets over in a new thread.
	try {
	    sender = new FileSendBuffer(destination, sendingPort, ackReceiver);
	} catch (SocketException e) {
	    try{
		System.err.println("[error] could not create a socket. message: " + e.getMessage());
	    	reader.close();
	    } catch(IOException r) {
		System.err.println("[error] file reader failed to close. message: " + r.getMessage());
	    } finally {
		System.exit(1);  	
	    }
	}

	ackReceiver.setSendBuffer(sender);
	ackReceiver.start();

	sender.start();

	// Create the initial packet to setup the transfer with
	// the receiver.
	DataPacket initPacket = new DataPacket(file, ackReceiver.getPort());
	sender.sendPacket(initPacket);

	// Read the file a chunk at a time and send it off in a packet. The FileSendBuffer
	// will store the packet in a buffer until it is ready to transmit, and retransmit
	// as necessary until it is acknowledged. If the buffer is full, the sendPacket
	// method will block until there is room.

	byte[] buffer = new byte[SEGMENT_SIZE];
	
	int bytesRead = 0;
	try {
	    bytesRead = reader.read(buffer);
	} catch (IOException e) {
	    System.err.println("[error] file read error. message: " + e.getMessage());
	    System.exit(1);
	}

	while (bytesRead != EOF) {
	    DataPacket filePacket = new DataPacket(buffer, bytesRead, false);
	    sender.sendPacket(filePacket);
	    try{
		bytesRead = reader.read(buffer);
	    } catch(IOException x) {
		System.err.println("[error] file read error. message: " + x.getMessage());
		System.exit(1);
	    }
	}

	// The final packet is used to signal the end of a transfer.
	DataPacket lastPacket = new DataPacket(null, 0, true);
	sender.sendPacket(lastPacket);

	// Wait for the last ACK packet to come in before terminating.
	ackReceiver.waitForAck(lastPacket.getSequenceNumber(), ACK_TIMEOUT);

	// Close both threads.
	ackReceiver.stopListening();
	sender.stopSending();

	try {
	    ackReceiver.join(THREAD_TIMEOUT);
	    sender.join(THREAD_TIMEOUT);
	} catch (InterruptedException e) {
	    System.err.println("[error] interrupted while closing threads. " + 
			       "transfer may not have finished normally.");
	}

	try {
	    sender.close();
	    reader.close();
	} catch(IOException x) {
	    System.err.println("[error] file reader failed to close: " + x.getMessage());
	}

	System.out.println("[completed]");

	long runningTime = new Date().getTime() - startTime;
	long totalDataSent = sender.getTotalDataSent();
	long fileSize = file.length();

	System.out.format("[stats] running time: %d ms\n", runningTime);
	System.out.format("[stats] file size: %d bytes\n", fileSize);
	System.out.format("[stats] total data sent: %d bytes\n", totalDataSent);
	double efficiency = 0.0;
	if (totalDataSent > 0) {
	    efficiency = (double)fileSize / (double)totalDataSent;
	}
	System.out.format("[stats] efficiency: %04.2f percent\n", efficiency * 100);
    }


    // Reads and validates the command line arguments. Prints out an error message
    // if they are formatted incorrectly.
    private boolean parseArgs(String[] args) {
	int argc = args.length;

	if (argc != 4) {
	    System.err.println("usage: sendfile -r <recv_host>:<recv_port> -f <filename>");
	    return false;
	}

	for (int i = 0; i < argc; i++){
			
	    if (args[i].equals("-r") && i + 1 < argc) {

		String[] hostArgs = args[i + 1].split(":", 2);
		if (hostArgs.length != 2) {
		    System.err.println("[error] destination must be in <recv_host>:<recv_port> format");
		    return false;
		}

		try {
		    destination = InetAddress.getByName(hostArgs[0]);
		} catch(UnknownHostException e) {
		    System.err.println("[error] <recv_host> must be in a.b.c.d format");
		    return false;
		}

		try {
		    sendingPort = Integer.parseInt(hostArgs[1]);
		} catch (NumberFormatException e) {
		    System.err.println("[error] <recv_port> must be an integer");
		    return false;
		}
	    }
			
	    // Verify that the file exists and is a normal file (i.e. not a directory, etc.).
	    if( args[i].equals("-f") && i + 1 < argc) {

		file = new File(args[i + 1]);

		if (!file.exists() || !file.isFile()) {
		    System.err.println("[error] file does not exist");
		    return false;
		}
	    }
	}
	
	return true;
    }

    public static void main (String [] args) {
	new Sender(args);
    }
}