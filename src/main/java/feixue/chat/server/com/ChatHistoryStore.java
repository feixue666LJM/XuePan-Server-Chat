package feixue.chat.server.com;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

// 聊天记录存储功能：从 chat_history 目录加载各群组历史记录并追加保存
class ChatHistoryStore {
    private final ChatServer server;

    ChatHistoryStore(ChatServer server) {
        this.server = server;
    }

    // 加载所有群组的聊天记录
    void loadChatHistory() {
        File historyDir = new File("chat_history");
        if (!historyDir.exists()) {
            return;
        }

        File[] groupFiles = historyDir.listFiles((dir, name) -> name.startsWith("group_") && name.endsWith(".txt"));
        if (groupFiles == null) return;

        for (File file : groupFiles) {
            String groupName = file.getName().substring(0, file.getName().length() - 4); // 移除 .txt 后缀
            List<ChatMessage> history = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 解析存储的消息格式: timestamp|sender|content
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        ChatMessage msg = new ChatMessage(parts[1], parts[2]);
                        msg.timestamp = Long.parseLong(parts[0]);
                        history.add(msg);
                    }
                }
            } catch (IOException | NumberFormatException e) {
                server.log("加载群组 " + groupName + " 的聊天记录失败: " + e.getMessage());
            }

            server.groupChatHistories.put(groupName, history);
            server.log("加载群组 " + groupName + " 的聊天记录，共 " + history.size() + " 条");
        }
    }

    // 保存聊天记录到文件
    void saveChatHistory(String group, ChatMessage message) {
        // 添加到内存中的历史记录
        server.groupChatHistories.computeIfAbsent(group, k -> new ArrayList<>()).add(message);

        // 保存到文件
        File historyDir = new File("chat_history");
        if (!historyDir.exists()) {
            historyDir.mkdirs();
        }

        File historyFile = new File(historyDir, group + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(historyFile, true))) {
            // 保存格式: timestamp|sender|content
            writer.println(message.timestamp + "|" + message.sender + "|" + message.content);
        } catch (IOException e) {
            server.log("保存群组 " + group + " 的聊天记录失败: " + e.getMessage());
        }
    }
}
