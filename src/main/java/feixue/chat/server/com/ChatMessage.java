package feixue.chat.server.com;

// 聊天消息类
class ChatMessage {
    String sender;
    String content;
    long timestamp;

    public ChatMessage(String sender, String content) {
        this.sender = sender;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "[" + sender + "] " + content;
    }
}
