package filetransfer;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.io.IOException;
import java.io.Closeable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.Arrays;
import java.util.Iterator;

// The FileReceiveBuffer accepts incoming packets from the sender
// and arranges them in the correct order.
public class FileReceiveBuffer extends Thread implements Closeable {

    private static final int MAX_PACKET_SIZE = 2000;

    private AckSender ackSender;
    private DatagramSocket socket;
    private DatagramPacket udpPacket;
    private boolean finishedReceiving;
    private ArrayList<DataPacket> buffer;
    private Lock lock;
    private Condition nextPacketAvailable;

    private int lastConsecutiveSeqNo;
    private int nextPacketSeqNo;

    private static final int BUFFER_SIZE = 1000;

    public FileReceiveBuffer(int port) throws SocketException {
	this.ackSender = null;
	this.socket = new DatagramSocket(port);
	this.udpPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
	this.finishedReceiving = false;
	this.buffer = new ArrayList<DataPacket>();
	this.lock = new ReentrantLock();
	this.nextPacketAvailable = lock.newCondition();

	this.nextPacketSeqNo = 0;
	this.lastConsecutiveSeqNo = -1;
    }

    public void close() {
	socket.close();
    }

    // Start a new thread running that will continuously listen for incoming data packets.
    public void run() {

	while (!finishedReceiving) {

	    // Zero out the buffer before receiving a new packet.
	    Arrays.fill(udpPacket.getData(), (byte)0);

	    try {
		socket.receive(udpPacket);
	    } catch (IOException e) {
		continue;
	    }

	    // Take the data from the UDP packet and create our own filetransfer
	    // data packet from it.
	    DataPacket packet = new DataPacket(udpPacket.getData());
	    
	    if (packet == null || packet.isCorrupt()) {
		System.out.println("[recv corrupt packet]");
		continue;
	    }

	    // In the beginning we don't know where to send the ACK value to, so
	    // if this is the first packet then it should contain the port that the
	    // sender is listening for ACK values on.
	    if (ackSender == null) {
		if (packet.isInitPacket()) {
		    int ackPort = packet.getAckPort();
		    InetAddress dest = udpPacket.getAddress();

		    try {
			ackSender = new AckSender(dest, ackPort);
		    } catch (SocketException e) {
			continue;
		    }
		}
	    }

	    // Store the packet in the buffer, even if it is out of order.
	    updateBuffer(packet);

	    // Only return an ACK value if we know where to send it.
	    if (ackSender != null) {
		ackSender.sendAck(lastConsecutiveSeqNo);
	    }

	}

    }


    // Gets the next consecutive packet from the sender. If the packet has not
    // yet arrived, this method will block until the packet becomes available.
    public DataPacket getNextPacket() {

	boolean packetFound = false;
	DataPacket packet = null;

	lock.lock();

	try {
	    while (!packetFound) {

		Iterator<DataPacket> iter = buffer.iterator();
		while (iter.hasNext()) {
		    packet = iter.next();
		    if (packet.getSequenceNumber() == nextPacketSeqNo) {
			iter.remove();
			packetFound = true;
			break;
		    }
		}

		// Use a condition variable to block while we are waiting.
		if (!packetFound) {
		    try {
			nextPacketAvailable.await();
		    } catch (InterruptedException e) {
			continue;
		    }
		}
	    }

	    ++nextPacketSeqNo;
	} finally {
	    lock.unlock();
	}

	return packet;
    }

    // Saves a packet to the buffer if it is within the range of acceptable packets.
    private void updateBuffer(DataPacket packet) {

	// A quick hack to figure out the starting position.
	final int SEGMENT_SIZE = 1000;
	int sequenceNumber = packet.getSequenceNumber();
	String start;
	if (packet.isInitPacket()) {
	    start = "start";
	} else if (packet.isLastPacket()) {
	    start = "end";
	} else {
	    start = Integer.toString((sequenceNumber - 1) * SEGMENT_SIZE);
	}
	int length = packet.getData().length;

	lock.lock();
	try {

	    // Only add the packet if we haven't received it before or if it's
	    // not too far ahead.
	    if (packetIsInBufferWindow(sequenceNumber)) {
		save(packet);
	    } else {
		System.out.format("[recv data] %s (%d) IGNORED\n", start, length);
		return;
	    }

	    if (sequenceNumber == nextPacketSeqNo) {
		System.out.format("[recv data] %s (%d) ACCEPTED(in-order)\n", start, length);
		nextPacketAvailable.signal();
	    } else {
		System.out.format("[recv data] %s (%d) ACCEPTED(out-of-order)\n", start, length);
	    }

	    if (sequenceNumber == lastConsecutiveSeqNo + 1) {
		updateLatestSequenceNumber();
	    }
	} finally {
	    lock.unlock();
	}
    }


    private boolean packetIsInBufferWindow(int sequenceNumber) {

	// Check if the sequence number is within the buffer window.
	if (sequenceNumber < nextPacketSeqNo ||
	    sequenceNumber >= nextPacketSeqNo + BUFFER_SIZE) {
	    return false;
	}

	// Check if the sequence number has already been saved in the buffer
	Iterator<DataPacket> iter = buffer.iterator();
	DataPacket packet = null;
	while (iter.hasNext()) {
	    packet = iter.next();
	    if (packet.getSequenceNumber() == sequenceNumber) {
		return false;
	    }
	}

	return true;
    }

    private void save(DataPacket packet) {
	buffer.add(packet);
    }

    // Goes through the buffer and determines where the last consecutive
    // sequence number received was.
    private void updateLatestSequenceNumber() {
	while (true) {
	    int i;
	    for (i = 0; i < buffer.size(); ++i) {
		DataPacket packet = (DataPacket)buffer.get(i);

		if (packet.getSequenceNumber() == lastConsecutiveSeqNo + 1) {
		    lastConsecutiveSeqNo += 1;
		    break;
		}
	    }

	    if (i == buffer.size()) {
		break;
	    }
	}
    }

    // Sends an acknowledgement of the last consecutive sequence number received.
    public void sendLastAck(int count) {
	for (int i = 0; i < count; ++i) {
	    ackSender.sendAck(lastConsecutiveSeqNo);
	}
    }

    public void stopListening() {

	finishedReceiving = true;

	// Close the socket that it is listening on as a way to
	// unblock the thread.
	socket.close(); 
    }
}