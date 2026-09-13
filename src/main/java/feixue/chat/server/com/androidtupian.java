package feixue.chat.server.com;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class androidtupian {
    // 图片块接收器类
    private static class ImageChunkReceiver {
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
    
    // 存储每个群组的图片接收器
    private static Map<String, ImageChunkReceiver> imageReceivers = new ConcurrentHashMap<>();
    
    /**
     * 处理图片信息消息
     * @param message 图片信息消息
     * @param logCallback 日志回调函数
     */
    public static void processImageInfo(String message, LogCallback logCallback) {
        try {
            String[] parts = message.split("\\|", 4);
            if (parts.length == 4) {
                String imageId = parts[1];
                int totalChunks = Integer.parseInt(parts[2]);
                String fileName = parts[3];
                
                logCallback.log("收到来自Android客户端的图片信息: " + fileName + " (" + totalChunks + " 块)");
            }
        } catch (Exception e) {
            logCallback.log("处理图片信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理图片块消息
     * @param message 图片块消息
     * @param logCallback 日志回调函数
     * @param saveImageCallback 保存图片回调函数
     */
    public static void processImageChunk(String message, LogCallback logCallback, SaveImageCallback saveImageCallback) {
        try {
            String[] parts = message.split("\\|", 4);
            if (parts.length == 4) {
                String imageId = parts[1];
                int chunkIndex = Integer.parseInt(parts[2]);
                String chunkData = parts[3];
                
                logCallback.log("收到来自Android客户端的图片块: " + imageId + " (" + chunkIndex + ")");
            }
        } catch (Exception e) {
            logCallback.log("处理图片块失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存图片到文件系统
     * @param imageData 图片数据
     * @param fileName 文件名
     * @param group 群组
     * @param sender 发送者
     * @param saveCallback 保存回调函数
     */
    public static void saveImageToFile(String imageData, String fileName, String group, String sender, SaveCallback saveCallback) {
        try {
            // 这里应该实现图片保存逻辑
            // 为了简化，我们只是模拟保存过程
            String filePath = "tupianbao/" + group + "/" + System.currentTimeMillis() + "_" + sender + "_" + fileName;
            saveCallback.onSaved(filePath);
        } catch (Exception e) {
            saveCallback.onError(e.getMessage());
        }
    }
    
    /**
     * 清理过期的图片接收器
     * @param logCallback 日志回调函数
     */
    public static void cleanupExpiredReceivers(LogCallback logCallback) {
        int count = 0;
        for (Map.Entry<String, ImageChunkReceiver> entry : imageReceivers.entrySet()) {
            if (entry.getValue().isExpired()) {
                imageReceivers.remove(entry.getKey());
                count++;
            }
        }
        if (count > 0) {
            logCallback.log("清理了 " + count + " 个过期的图片接收器");
        }
    }
    
    // 回调接口定义
    public interface LogCallback {
        void log(String message);
    }
    
    public interface SaveImageCallback {
        void onImageSaved(String filePath);
        void onError(String error);
    }
    
    public interface SaveCallback {
        void onSaved(String filePath);
        void onError(String error);
    }
}
