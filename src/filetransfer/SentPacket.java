package filetransfer;

public class SentPacket {
    public byte[] data;
    public int sequenceNumber;
    public int sendCount;
    public boolean isInitPacket;
    public boolean isLastPacket;
}