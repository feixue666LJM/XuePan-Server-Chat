package feixue.chat.server.com;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 消息校验功能：字节长度限制、连续相同字符检测、违禁词检测与过滤、重复消息检测、版本号校验
class MessageGuard {
    private final ChatServer server;

    MessageGuard(ChatServer server) {
        this.server = server;
    }

    // 从pbc.txt加载屏蔽词列表，若文件不存在或为空则使用代码中的默认值
    void loadForbiddenWords() {
        File pbcFile = new File("pbc.txt");
        if (!pbcFile.exists()) {
            server.log("pbc.txt 不存在，使用代码中的默认屏蔽词列表");
            return;
        }

        Set<String> loadedWords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(pbcFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    loadedWords.add(word);
                }
            }
        } catch (IOException e) {
            server.log("加载屏蔽词列表失败: " + e.getMessage());
            return;
        }

        if (loadedWords.isEmpty()) {
            server.log("pbc.txt 为空，使用代码中的默认屏蔽词列表");
        } else {
            server.forbiddenWords = loadedWords;
            server.log("从 pbc.txt 加载屏蔽词列表，共 " + loadedWords.size() + " 个屏蔽词");
        }
    }

    // 检查消息是否超过字节限制
    boolean isMessageTooLong(String message) {
        try {
            byte[] messageBytes = message.getBytes("UTF-8");
            return messageBytes.length > ChatServer.MAX_MESSAGE_BYTES;
        } catch (Exception e) {
            return true; // 出现异常时认为消息过长
        }
    }

    // 检查用户ID是否超过字节限制
    boolean isUserIdTooLong(String userId) {
        try {
            byte[] userIdBytes = userId.getBytes("UTF-8");
            return userIdBytes.length > ChatServer.MAX_USER_ID_BYTES;
        } catch (Exception e) {
            return true; // 出现异常时认为用户ID过长
        }
    }

    // 检查消息中是否包含连续5个相同的字符
    boolean hasTooManyConsecutiveSameChars(String message) {
        if (message == null || message.length() < ChatServer.MAX_CONSECUTIVE_SAME_CHARS) {
            return false;
        }

        int consecutiveCount = 1;
        char previousChar = message.charAt(0);

        for (int i = 1; i < message.length(); i++) {
            char currentChar = message.charAt(i);
            if (currentChar == previousChar) {
                consecutiveCount++;
                if (consecutiveCount >= ChatServer.MAX_CONSECUTIVE_SAME_CHARS) {
                    return true;
                }
            } else {
                consecutiveCount = 1;
                previousChar = currentChar;
            }
        }

        return false;
    }
    
    // 检查消息是否包含违禁词
    boolean containsForbiddenWords(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        String lowerMessage = message.toLowerCase();
        for (String forbiddenWord : server.forbiddenWords) {
            if (lowerMessage.contains(forbiddenWord.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    // 过滤消息中的违禁词（用*替换）
    String filterForbiddenWords(String message) {
        if (message == null || message.trim().isEmpty()) {
            return message;
        }
        
        String filteredMessage = message;
        for (String forbiddenWord : server.forbiddenWords) {
            String lowerForbidden = forbiddenWord.toLowerCase();
            String lowerMessage = filteredMessage.toLowerCase();
            int index = lowerMessage.indexOf(lowerForbidden);
            while (index != -1) {
                // 用星号替换违禁词
                StringBuilder sb = new StringBuilder(filteredMessage);
                for (int i = 0; i < forbiddenWord.length(); i++) {
                    sb.setCharAt(index + i, '*');
                }
                filteredMessage = sb.toString();
                lowerMessage = filteredMessage.toLowerCase();
                index = lowerMessage.indexOf(lowerForbidden, index + forbiddenWord.length());
            }
        }
        return filteredMessage;
    }

    // 清理过期的重复消息记录
    void cleanupRecentMessages(String group) {
        List<RecentMessage> messages = server.recentMessages.get(group);
        if (messages != null) {
            long currentTime = System.currentTimeMillis();
            messages.removeIf(msg -> (currentTime - msg.timestamp) > ChatServer.MESSAGE_DUPLICATE_WINDOW);
        }
    }

    // 检查消息是否重复
    boolean isDuplicateMessage(String group, String message) {
        // 语音消息跳过重复检测
        if (message.startsWith("/voice|") || message.startsWith("/voice_with_sender|")) {
            return false;
        }
        
        List<RecentMessage> messages = server.recentMessages.computeIfAbsent(group, k -> new java.util.ArrayList<>());

        // 清理过期消息
        cleanupRecentMessages(group);

        // 检查是否有重复消息
        long currentTime = System.currentTimeMillis();
        for (RecentMessage recentMsg : messages) {
            if (recentMsg.content.equals(message)) {
                return true;
            }
        }

        // 添加新消息到记录中
        messages.add(new RecentMessage(message, currentTime));
        return false;
    }

    // 验证客户端版本是否兼容
    boolean isVersionCompatible(String clientVersion) {
        return compareVersionNumbers(clientVersion, server.minimumClientVersion) >= 0;
    }

    int compareVersionNumbers(String left, String right) {
        if (left == null || right == null) {
            return -1;
        }
        String[] leftParts = left.trim().split("\\.");
        String[] rightParts = right.trim().split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int leftPart;
            int rightPart;
            try {
                leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
                rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            } catch (NumberFormatException e) {
                return -1;
            }
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    boolean isValidVersionNumber(String version) {
        if (version == null || !ChatServer.VERSION_NUMBER_PATTERN.matcher(version).matches()) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            for (String part : parts) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
