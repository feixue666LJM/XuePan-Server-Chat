package feixue.chat.server.com;

import java.util.HashMap;
import java.util.Map;

// 图片块接收器类（服务端接收并重组客户端上传的分块图片）
class ImageChunkReceiver {
    private String imageId;
    private String fileName;
    private String group;
    private String sender;
    private int totalChunks;
    private Map<Integer, String> receivedChunks;
    private long lastUpdateTime;

    public ImageChunkReceiver(String imageId, String fileName, String group, String sender, int totalChunks) {
        this.imageId = imageId;
        this.fileName = fileName;
        this.group = group;
        this.sender = sender;
        this.totalChunks = totalChunks;
        this.receivedChunks = new HashMap<>();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public boolean addChunk(int chunkIndex, String chunkData) {
        receivedChunks.put(chunkIndex, chunkData);
        lastUpdateTime = System.currentTimeMillis();
        return receivedChunks.size() == totalChunks;
    }

    public String getCompleteImageData() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalChunks; i++) {
            String chunk = receivedChunks.get(i);
            if (chunk != null) {
                sb.append(chunk);
            }
        }
        return sb.toString();
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - lastUpdateTime > 30000; // 30秒超时
    }

    public String getImageId() {
        return imageId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getGroup() {
        return group;
    }

    public String getSender() {
        return sender;
    }
}
