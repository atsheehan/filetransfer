BUILD_DIR = bin
SRC_DIR = src/filetransfer
COMMON_FILES = $(SRC_DIR)/DataPacket.java
SENDER_FILES = $(SRC_DIR)/Sender.java $(SRC_DIR)/FileSendBuffer.java $(SRC_DIR)/SentPacket.java $(SRC_DIR)/AckReceiver.java $(COMMON_FILES)
RECEIVER_FILES = $(SRC_DIR)/Receiver.java $(SRC_DIR)/FileReceiveBuffer.java $(SRC_DIR)/AckSender.java $(COMMON_FILES)

build: $(SENDER_FILES) $(RECEIVER_FILES)
	mkdir -p $(BUILD_DIR)
	javac -d $(BUILD_DIR) $(SENDER_FILES)
	javac -d $(BUILD_DIR) $(RECEIVER_FILES)
	jar cvfm sendfile.jar senderManifest.mf -C $(BUILD_DIR) filetransfer
	jar cvfm recvfile.jar receiverManifest.mf -C $(BUILD_DIR) filetransfer

clean:
	-rm -rf $(BUILD_DIR) recvfile.jar sendfile.jar
