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
    private int previousAckReceived;
    private int lastAckReceived;
    private boolean doneListening;
    private Lock lock;
    private Condition ackReceived;
    private FileSendBuffer sender;

    private static final int ACK_PACKET_SIZE = 12;

    // Creates a new receiver that will listen on a random, open port for
    // ACKs and will send updates to the given FileSendBuffer.
    public AckReceiver() throws SocketException {
	this.socket = new DatagramSocket();
	this.buffer = ByteBuffer.allocate(ACK_PACKET_SIZE);
	this.packet = new DatagramPacket(buffer.array(), ACK_PACKET_SIZE);

	this.previousAckReceived = -1;
	this.lastAckReceived  = -1;
	this.doneListening    = false;

	this.sender = null;

	this.lock = new ReentrantLock();
	this.ackReceived = lock.newCondition();
    }

    // Assigns a send buffer to notify of incoming ACK packets.
    public void setSendBuffer(FileSendBuffer sender) {
	this.sender = sender;
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

	    // Verify that the ACK contains three identical integer
	    // values.
	    if (buffer.capacity() < ACK_PACKET_SIZE) {
		System.out.println("[recv corrupt ack]");
		continue;
	    }

	    int ackValue = buffer.getInt(0);
	    if (ackValue != buffer.getInt(4) ||
		ackValue != buffer.getInt(8)) {
		System.out.println("[recv corrupt ack]");
		continue;
	    }

	    // When an ACK is received, notify the FileSendBuffer
	    // so that it can stop transmitting that packet.
	    System.out.format("[recv ack] %d\n", ackValue);
	    updateLastAckReceived(ackValue);

	    if (sender != null) {
		sender.setLastAck((int)lastAckReceived);

		// If two of the same ACK value were received in a row,
		// notify the sender to re-send the packet after that
		// since it probably went missing.
		if (previousAckReceived == ackValue) {
		    sender.resendPacket(ackValue + 1);
		}
	    }

	    previousAckReceived = ackValue;
	}

	socket.close();
    }

    // Blocks until we receive the expected ACK. Returns true if the ACK
    // was received, returns false if interrupted or times-out.
    public boolean waitForAck(int expectedAck, long timeoutInMS) {
	lock.lock();

	try {
	    while (lastAckReceived < expectedAck ) {
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
    private void updateLastAckReceived(int newAck) {
	lock.lock();
	try {
	    if (newAck >= lastAckReceived) {
		lastAckReceived = newAck;
		ackReceived.signal();
	    }

	} finally {
	    lock.unlock();
	}
    }

}