package filetransfer;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.io.IOException;

// Sends ACK messages over a specific port.
public class AckSender {

    private DatagramSocket socket;
    private DatagramPacket packet;
    private ByteBuffer buffer;

    private int latestAck;

    private final static int ACK_SIZE = 12;

    // Creates the sender and opens a new socket.
    public AckSender(InetAddress destination, int ackPort)  throws SocketException {
	this.socket	 = new DatagramSocket();
	this.buffer	 = ByteBuffer.allocate(ACK_SIZE);
	this.packet	 = new DatagramPacket(buffer.array(), ACK_SIZE, destination, ackPort);
	this.latestAck   = 0;
    }

    // Sends the latest ACK message received back to the sender.
    public boolean sendAck(int ackNumber) {

	if (ackNumber > latestAck) {
	    latestAck = ackNumber;
	}

	// Put three copies of the same value in so the receiver
	// can verify that they are the same (easier than a checksum).
	buffer.putInt(0, latestAck);
	buffer.putInt(4, latestAck);
	buffer.putInt(8, latestAck);

	try {
	    socket.send(packet);
	} catch (IOException e) {
	    return false;
	}
	System.out.format("[send ack] %d\n", ackNumber);

	return true;
    }

}