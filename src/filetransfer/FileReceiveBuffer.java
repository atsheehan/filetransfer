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

    public void run() {

	while (!finishedReceiving) {

	    // Zero out the buffer before receiving a new packet.
	    Arrays.fill(udpPacket.getData(), (byte)0);

	    try {
		socket.receive(udpPacket);
	    } catch (IOException e) {
		System.exit(1);
	    }

	    DataPacket packet = new DataPacket(udpPacket.getData());
	    
	    if (packet == null || packet.isCorrupt()) {
		System.err.println("[recv corrupt packet]");
		continue;
	    }

	    if (ackSender == null) {
		if (packet.isInitPacket()) {
		    int ackPort = packet.getAckPort();
		    InetAddress dest = udpPacket.getAddress();

		    try {
			ackSender = new AckSender(dest, ackPort);
		    } catch (SocketException e) {
			System.exit(1); // TODO
		    }
		}
	    }

	    updateBuffer(packet);

	    if (ackSender != null) {
		ackSender.sendAck(lastConsecutiveSeqNo);
	    }

	}

    }

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
	    if (packetIsInBufferWindow(sequenceNumber)) {
		save(packet);
	    } else {
		System.err.format("[recv data] %s (%d) IGNORED\n", start, length);
		return;
	    }

	    if (sequenceNumber == nextPacketSeqNo) {
		System.err.format("[recv data] %s (%d) ACCEPTED(in-order)\n", start, length);
		nextPacketAvailable.signal();
	    } else {
		System.err.format("[recv data] %s (%d) ACCEPTED(out-of-order)\n", start, length);
	    }

	    if (sequenceNumber == lastConsecutiveSeqNo + 1) {
		updateLatestSequenceNumber();
	    }
	} finally {
	    lock.unlock();
	}
    }

    private boolean packetIsInBufferWindow(int sequenceNumber) {
	// Check if the sequence number is within the buffer window
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