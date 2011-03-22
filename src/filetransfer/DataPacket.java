package filetransfer;

import java.nio.ByteBuffer;
import java.io.File;

// Represents a packet used to transfer information from a sender to a receiver.
public class DataPacket {

    private boolean isCorrupt;
    private boolean isFirstPacket;
    private boolean isLastPacket;
    private String filename;
    private int ackPort;
    private int sequenceNumber;
    private byte[] data;
	
    static public final int HEADER_SIZE = 9;

    static final int	SEQUENCE_NO_INDEX   = 0;
    static final int	CHECKSUM_INDEX	    = 4;
    static final int	PACKET_LENGTH_INDEX = 6;
    static final int	FLAG_INDEX	    = 8;
    static final int	DATA_INDEX	    = HEADER_SIZE;

    static final byte	FIRST_PACKET_FLAG = 0x01;
    static final byte	LAST_PACKET_FLAG  = 0x02;



    // Initialize all of the member variables to default values.
    private DataPacket() {
	this.isCorrupt	    = false;
	this.isFirstPacket  = false;
	this.isLastPacket   = false;
	this.data	    = null;
	this.filename	    = null;
	this.sequenceNumber = -1;
	this.ackPort	    = -1;
    }

    // Construct a new data packet from a serialized byte stream.
    public DataPacket(byte[] data) {

	this();

	// Verify that the packet has a header and that the checksum
	// is valid to detect for corrupted packets.
	ByteBuffer buffer = ByteBuffer.wrap(data);
	if (buffer.capacity() < HEADER_SIZE) {
	    isCorrupt = true;
	    return;
	}

	if (!isChecksumValid(buffer.array())) {
	    isCorrupt = true;
	    return;
	}

	// Read the header information from the first couple of bytes.
	byte flags = buffer.get(FLAG_INDEX);
	short packetLength = buffer.getShort(PACKET_LENGTH_INDEX);
	this.sequenceNumber = buffer.getInt(SEQUENCE_NO_INDEX);

	if ((flags & FIRST_PACKET_FLAG) > 0) this.isFirstPacket = true;
	if ((flags & LAST_PACKET_FLAG) > 0) this.isLastPacket = true;


	// Verify that the packet is as long as it says it is.
	if (buffer.capacity() < packetLength) {
	    this.isCorrupt = true;
	    return;
	}

	// Copy the data into the packets byte buffer.
	// If this is the first packet, then parse the initialization data. 
	// Otherwise, just copy everything into the data section.
	this.data = new byte[packetLength - HEADER_SIZE];
	buffer.position(DATA_INDEX);

	if (this.isFirstPacket) {

	    // Verify there is enough space in the data section for the
	    // init info. There should be 4 bytes containing the ACK port
	    // and then the remaining bytes should be for the filename.
	    if (buffer.remaining() < 4) {
		this.isCorrupt = true;
		return;
	    }

	    this.ackPort = buffer.getInt();

	    int filenameSize = buffer.remaining();
	    byte[] filenameBytes = new byte[filenameSize];
	    buffer.get(filenameBytes);
	    this.filename = new String(filenameBytes);

	} else {

	    // Contains file data so just copy everything.
	    buffer.get(this.data);
	}
    }

    // Creates a new init packet for the given file.
    public DataPacket(File file, int ackPort) {

	this();

	// Since this is the initialization packet, we know it is the first packet
	// and will not be the last packet.

	this.isFirstPacket = true;
	this.filename	   = String.format("%s.recv", file.getName());
	this.sequenceNumber = 0;

	// The data section should consist of 4 bytes for the ACK port and
	// then the rest of the bytes should be for the filename.

	byte[] filenameBytes = filename.getBytes();
	int dataLength = filenameBytes.length + 4;

	ByteBuffer buffer = ByteBuffer.allocate(dataLength);
	buffer.putInt(ackPort);
	buffer.put(filenameBytes);

	this.data = buffer.array();
    }

    // Creates a packet with file data.
    public DataPacket(byte[] data, int dataLength, boolean isLastPacket) {
	
	this();

	this.isLastPacket = isLastPacket;
	this.data = new byte[dataLength];

	if (data != null) {
	    System.arraycopy(data, 0, this.data, 0, dataLength);
	}
    }


    // Indicates whether this packet signals the end of a transfer.
    public boolean isLastPacket() {
	return isLastPacket;
    }

    // Indicates whether this file contains the transfer initialization info.
    public boolean isInitPacket() {
	return isFirstPacket;
    }

    // Indicates whether a packet was properly de-serialized.
    public boolean isCorrupt() {
	return isCorrupt;
    }

    // Gets the name of the file being transferred. Only valid for init packets. 
    public String getFilename() {
	return filename + ".xml";
    }

    // Gets the port that the sender is listening for ACKs on. Only valid for init packets.
    public int getAckPort() {
	return ackPort;
    }

    // Gets the sequence number for the packet.
    public int getSequenceNumber() {
	return sequenceNumber;
    }

    // Assigns a sequence number to the packet.
    public void setSequenceNumber(int value) {
	sequenceNumber = value;
    }

    // Gets the data section of the packet.
    public byte[] getData() {
	return data;
    }

    // Converts a packet object into an array of bytes for transmitting
    // over the network.
    public byte[] serialize() {

	int packetSize = data.length + HEADER_SIZE;
	ByteBuffer buffer = ByteBuffer.allocate(packetSize);

	byte flags = 0;
	if (isFirstPacket) flags |= FIRST_PACKET_FLAG;
	if (isLastPacket) flags |= LAST_PACKET_FLAG;

	// Fill in the packet header info in the first couple of bytes.
	buffer.putInt(SEQUENCE_NO_INDEX, sequenceNumber);
	buffer.putShort(CHECKSUM_INDEX, (short)0);
	buffer.putShort(PACKET_LENGTH_INDEX, (short)(packetSize));
	buffer.put(FLAG_INDEX, flags); 

	// Set the buffer position to the start of the data section and
	// copy in the packet data.
	buffer.position(DATA_INDEX);
	buffer.put(data);

	// Calculate the checksum (with the checksum field set to 0) and then
	// copy it into the header.
	short checksum = (short)calculateChecksum(buffer.array());
	buffer.putShort(CHECKSUM_INDEX, checksum);

	return buffer.array();
    }


    // Verifies that the checksum of the given buffer is correct.
    private static boolean isChecksumValid(byte[] buffer) {
	return (calculateChecksum(buffer) == 0);
    }


    // Computes the checksum of the given byte array.
    private static long calculateChecksum(byte[] buffer) {

	// Note: code is influenced by lecture slides as well as the info at:
	// http://stackoverflow.com/questions/4113890/
	// how-to-calculate-the-internet-checksum-from-a-byte-in-java

	// Compute the checksum by adding up all of the 16-bit segments 
	// using one's complement arithmetic and then return the inverse
	// of the sum.

	long sum = 0;
	long data;

	int length = buffer.length;
	int i = 0;

	// Read the values of the byte array in pairs to form a 16-bit word by shifting
	// the first value by 8 bits to the left. Keep a running total of the words and
	// if there is any overflow past the 16-bits, discard the extra bits and add
	// one to the total.
	while (length > 1) {

	    data = (((buffer[i] << 8) & 0xFF00) | ((buffer[i + 1]) & 0xFF));
	    sum += data;

	    if ((sum & 0xFFFF0000) > 0) {
		sum = sum & 0xFFFF;
		sum += 1;
	    }

	    i += 2;
	    length -= 2;
	}

	// Handle the last byte if necessary. Need to shift the byte 8 bits to the
	// left so it occupies the first byte segment of a 16-bit word.
	if (length > 0) {
	    
	    sum += (buffer[i] << 8 & 0xFF00);

	    if ((sum & 0xFFFF0000) > 0) {
	    	sum = sum & 0xFFFF;
	    	sum += 1;
	    }
	}

	// Invert the value and return 16 bits of data.  
	sum = ~sum;
	sum = sum & 0xFFFF;

	return sum;
    }
}
