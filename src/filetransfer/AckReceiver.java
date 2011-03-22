package filetransfer;

import java.net.SocketException;
import java.net.DatagramSocket;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.DatagramPacket;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.TimeUnit;

// Accepts incoming ACKs from the file receiver and notifies
// the FileSendBuffer that a packet was successfully transmitted.
public class AckReceiver extends Thread {

    private DatagramSocket socket;
    private DatagramPacket packet;
    private ByteBuffer buffer;
    private long lastAckReceived;
    private boolean doneListening;
    private Lock lock;
    private Condition ackReceived;
    private FileSendBuffer sender;

    private static final int ACK_PACKET_SIZE = 8;

    // Creates a new receiver that will listen on a random, open port for
    // ACKs and will send updates to the given FileSendBuffer.
    public AckReceiver(FileSendBuffer sender) throws SocketException {
	this.socket = new DatagramSocket();
	this.buffer = ByteBuffer.allocate(ACK_PACKET_SIZE);
	this.packet = new DatagramPacket(buffer.array(), ACK_PACKET_SIZE);

	this.lastAckReceived  = -1;
	this.doneListening    = false;

	this.sender = sender;

	this.lock = new ReentrantLock();
	this.ackReceived = lock.newCondition();
    }

    // Gets the port that the receiver is listening on.
    public int getPort() {
	return socket.getLocalPort();
    }

    // Starts the thread listening for incoming ACK values.
    public void run() {

	while (!doneListening) {

	    try {
		socket.receive(packet);
	    } catch (IOException e) {
		continue;
	    }

	    // When an ACK is received, notify the FileSendBuffer
	    // so that it can stop transmitting that packet.
	    updateLastAckReceived(buffer.getLong(0));
	    sender.setLastAck((int)lastAckReceived);
	}

	socket.close();
    }

    // Blocks until we receive the expected ACK. Returns true if the ACK
    // was received, returns false if interrupted or times-out.
    public boolean waitForAck(long expectedAck, long timeoutInMS) {
	lock.lock();

	try {
	    while (expectedAck > lastAckReceived) {
		try {
		    if (!ackReceived.await(timeoutInMS, TimeUnit.MILLISECONDS)) {
			return false;
		    }
		} catch (InterruptedException e) {
		    return false;
		}
	    }
	} finally {
	    lock.unlock();
	}

	return true;
    }

    // Stops the thread from listening by closing the socket.
    public void stopListening() {

	doneListening = true;

	// Close the socket that it is listening on as a way to
	// unblock the thread.
	socket.close(); 
    }

    // Determines if the latest ACK is greater than the previous
    // one, and if so, updates the value and alerts any threads
    // waiting on a new ACK value.
    private void updateLastAckReceived(long newAck) {
	lock.lock();
	try {
	    if (newAck > lastAckReceived) {
		lastAckReceived = newAck;
		ackReceived.signal();
	    }

	} finally {
	    lock.unlock();
	}
    }

}