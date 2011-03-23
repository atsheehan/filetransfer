package filetransfer;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.Semaphore;
import java.util.ArrayList;
import java.util.Iterator;
import java.net.SocketException;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

// The FileSendBuffer queues up packets to send over the network, retransmitting
// as necessary until the appropriate acknowledgement is received.
public class FileSendBuffer extends Thread implements Closeable {

    private DatagramSocket socket;
    private InetAddress destination;
    private int port;
    private Semaphore bufferSlots;
    private ArrayList<SentPacket> buffer;
    private boolean doneTransfer;
    private ReentrantLock lock;
    private AckReceiver ackReceiver;

    private int lastAckSeqNo;
    private int nextSeqNo;
    private int bufferSize;

    private long totalDataSent;

    private static final long ACK_TIMEOUT = 100;
    private static final int MIN_BUFFER_SIZE = 2;
    private static final int MAX_BUFFER_SIZE = 100;
    private static final int BUFFER_STEP_SIZE = 2;

    // Initializes the buffer to send packets to the supplied destination.
    public FileSendBuffer(InetAddress destination, int port, AckReceiver ackReceiver) throws SocketException {

	this.socket	  = new DatagramSocket();
	this.destination  = destination;
	this.port	  = port;
	this.doneTransfer = false;
	this.nextSeqNo	  = 0;
	this.ackReceiver  = ackReceiver;

	this.buffer	  = new ArrayList<SentPacket>();
	this.bufferSlots  = new Semaphore(MIN_BUFFER_SIZE);
	this.lock	  = new ReentrantLock();

	this.totalDataSent = 0;
    }


    // Adds a new packet to the queue to be sent to the receiver. If the buffer queue
    // is full, this method will block until space becomes available.
    public void sendPacket(DataPacket packet) {

	// A semaphore value is used to represent available slots in the buffer queue.
	// If there are no slots available, trying to acquire the semaphore will result
	// in the thread being blocked.
	boolean slotAcquired = false;
	while (!slotAcquired) {
	    try {
		bufferSlots.acquire();
		slotAcquired = true;
	    } catch (InterruptedException e) {
		continue;
	    }
	}

	// Go ahead and add the packet to the buffer. A lock is used to synchronize access
	// to the list.
	lock.lock();
	try {
	    // A sequence number is assigned to the packet and then it is serialized for
	    // transfer over the network. The serialized data is stored in a SentPacket
	    // structure so it does not have to be serialized again if we have to re-transmit.
	    packet.setSequenceNumber(nextSeqNo);
	    ++nextSeqNo;

	    SentPacket packetInfo = new SentPacket();
	    packetInfo.data = packet.serialize();
	    packetInfo.sequenceNumber = packet.getSequenceNumber();
	    packetInfo.sendCount = 0;
	    packetInfo.isInitPacket = packet.isInitPacket();
	    packetInfo.isLastPacket = packet.isLastPacket();

	    buffer.add(packetInfo);
	} finally {
	    lock.unlock();
	}


    }

    // Informs the buffer of the last acknowledged packet so it may discard any packets
    // it does not need to retransmit again.
    public void setLastAck(int lastAckValue) {

	// Go through the buffer and discard any of the packets that have a sequence
	// number equal to or less than the last ACK value.
	lock.lock();
	try {

	    Iterator<SentPacket> iter = buffer.iterator();
	    while (iter.hasNext()) {
		SentPacket packet = iter.next();
		if (packet.sequenceNumber <= lastAckValue) {
		    iter.remove();
		    bufferSlots.release();
		}
	    }
	} finally {
	    lock.unlock();
	}
    }

    // While running, this thread will continually transmit packets that are in the send buffer queue.
    public void run() {

	while (!doneTransfer) {

	    // Gets the next packet to send from the buffer, which is determined based on the 
	    // sequence number and the number of times the packet has already been transmitted. 
	    SentPacket nextPacket = getNextPacketToSend();
	    if (nextPacket == null) {

		// No packets to send.
		continue;
	    }

	    // Wrap up the data in a UDP datagram and send it out.
	    DatagramPacket udpPacket = new DatagramPacket(nextPacket.data, nextPacket.data.length, destination, port);
	    try {
		socket.send(udpPacket);
	    } catch (IOException e) {
		continue;
	    }

	    totalDataSent += udpPacket.getLength();

	    // Display some info about the data.
	    String startIndex;
	    if (nextPacket.isInitPacket) {
		startIndex = "start";
	    } else if (nextPacket.isLastPacket) {
		startIndex = "end";
	    } else {
		startIndex = Integer.toString((nextPacket.sequenceNumber - 1) * Sender.SEGMENT_SIZE);
	    }

	    // Use the err output to display immediately.
	    System.err.format("[send data] %s (%d)\n", 
			      startIndex,
			      nextPacket.data.length - DataPacket.HEADER_SIZE);

	    nextPacket.sendCount = 1;
	}

    }

    // Returns the amount of data that has been sent so far.
    public long getTotalDataSent() {
	return totalDataSent;
    }

    // Gets the next packet in the buffer to send based on the sequence numbers and the
    // number of times it has been transmitted in the past. Returns null when no packets
    // should be transmitted.
    private SentPacket getNextPacketToSend() {
	SentPacket packetToSend = null;
	    
	lock.lock();
	try {
	    // Iterate through the buffer and choose the packet with the smallest send
	    // count. If there is a tie, then choose the packet with the smallest sequence
	    // number. If there is a tie again, just choose the first one.
	    Iterator<SentPacket> iter = buffer.iterator();
	    while (iter.hasNext()) {
		SentPacket packet = iter.next();
		if (packetToSend == null) {
		    packetToSend = packet;
		    continue;
		}

		if (packet.sendCount < packetToSend.sendCount) {
		    packetToSend = packet;
		    continue;
		}

		if (packet.sendCount == packetToSend.sendCount &&
		    packet.sequenceNumber < packetToSend.sequenceNumber) {
		    packetToSend = packet;
		}
	    }
	} finally {
	    lock.unlock();
	}

	// If this packet has been sent before, that means that all of the packets
	// in the buffer have already been sent. Try waiting for an ACK to come in
	// for a short period. If an ACK is received, then we can assume that we can
	// increase our buffer to fill in that empty space. If no ACK comes in, we
	// assume that the packet was lost or corrupted and we can send it again.
	if (packetToSend != null &&
	    packetToSend.sendCount > 0) {

	    if (ackReceiver.waitForAck(packetToSend.sequenceNumber, ACK_TIMEOUT)) {

		// Expand the buffer a bit.
		expandBuffer();
		return null;

	    } else {
		//resetSendCounts();
		return packetToSend;
	    }
	}

	return packetToSend;
    }

    // Signals the thread to stop sending packets from the buffer by closing
    // the socket.
    public void stopSending() {
	doneTransfer = true;
	socket.close();
    }

    // Closes the underlying socket.
    public void close() {
	socket.close();
    }

    private void expandBuffer() {
	lock.lock();

	try {
	    if (buffer.size() + bufferSlots.availablePermits() <= MAX_BUFFER_SIZE) {
		System.err.println("[debug] expanding buffer");
		bufferSlots.release(BUFFER_STEP_SIZE);
	    }
	} finally {
	    lock.unlock();
	}
    }

    // Marks a packet's send count to 0 so that it will be prioritized
    // in the send buffer.
    public void resendPacket(int sequenceNumber) {
	int start = sequenceNumber;
	int end = sequenceNumber + 1;

	lock.lock();
	try {
	    Iterator<SentPacket> iter = buffer.iterator();
	    while (iter.hasNext()) {
		SentPacket packet = iter.next();
		if (packet.sequenceNumber >= start &&
		    packet.sequenceNumber <= end) {
		    packet.sendCount = 0;
		}
	    }
	} finally {
	    lock.unlock();
	}
    }

   private void resetSendCounts() {
	System.err.println("[debug] timed out. resetting send counts");
	lock.lock();
	try {
	    Iterator<SentPacket> iter = buffer.iterator();
	    while (iter.hasNext()) {
		SentPacket packet = iter.next();
		packet.sendCount = 0;
	    }
	} finally {
	    lock.unlock();
	}
    }
}