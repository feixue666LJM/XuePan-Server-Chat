package feixue.chat.server.com;

// 最近消息类，用于重复消息检测
class RecentMessage {
    String content;
    long timestamp;

    public RecentMessage(String content, long timestamp) {
        this.content = content;
        this.timestamp = timestamp;
    }
}
