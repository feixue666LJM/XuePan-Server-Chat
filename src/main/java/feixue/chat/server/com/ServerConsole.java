package feixue.chat.server.com;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// 服务器控制台功能：服务器命令行输入处理、服务器广播与频道定向消息
class ServerConsole {
    private final ChatServer server;

    ServerConsole(ChatServer server) {
        this.server = server;
    }

    // 处理服务器命令
    void handleServerCommand(String command) {
        if (command.startsWith("/chat ")) {
            // 发送聊天消息
            String message = command.substring(6);
            server.log("[server] " + message);
            broadcastFromServer(message);
        } else if (command.startsWith("/ban ")) {
            // 禁止用户
            String username = command.substring(5).trim();
            if (!username.isEmpty()) {
                server.userManager.banUser(username);
                server.log("服务器: 已禁止用户 " + username);
            }
        } else if (command.startsWith("/unban ")) {
            // 解除禁止
            String username = command.substring(7).trim();
            if (!username.isEmpty()) {
                server.userManager.unbanUser(username);
                server.log("服务器: 已解除禁止用户 " + username);
            }
        } else if (command.startsWith("/kick ")) {
            String username = command.substring(6).trim();
            if (!username.isEmpty()) {
                server.userManager.kickUser(username);
            } else {
                server.log("用法: /kick 用户名");
            }
        } else if (command.startsWith("/mute ")) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length != 3) {
                server.log("用法: /mute 用户名 小时数");
                return;
            }
            try {
                double hours = Double.parseDouble(parts[2]);
                if (hours <= 0) {
                    server.log("禁言时间必须大于0小时");
                    return;
                }
                server.userManager.muteUser(parts[1], hours);
            } catch (NumberFormatException e) {
                server.log("禁言时间必须是数字，单位为小时");
            }
        } else if (command.equals("/unmute") || command.startsWith("/unmute ")) {
            String username = command.length() > 7 ? command.substring(8).trim() : "";
            if (username.isEmpty()) {
                server.log("用法: /unmute 用户名");
                return;
            }
            server.userManager.unmuteUser(username);
        } else if (command.startsWith("/chatone ")) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length != 3 || parts[2].trim().isEmpty()) {
                server.log("用法: /chatone 频道名 消息内容");
                return;
            }
            sendServerMessageToGroup(parts[1], parts[2].trim());
        } else {
            server.log("未知命令: " + command);
        }
    }

    // 广播消息来自服务器
    void broadcastFromServer(String message) {
        // 遍历所有群组
        for (Map.Entry<String, List<ClientHandler>> entry : server.groups.entrySet()) {
            String group = entry.getKey();
            List<ClientHandler> clients = entry.getValue();

            // 检查是否为公共频道，如果是则进行违禁词检测
            String messageToBroadcast = message;
            if (ChatServer.PUBLIC_CHANNEL_GROUP.equals(group)) {
                // 检查是否包含违禁词
                if (server.messageGuard.containsForbiddenWords(message)) {
                    server.log("服务器消息检测到违禁词，消息将被过滤: " + message);
                    messageToBroadcast = server.messageGuard.filterForbiddenWords(message);
                }
            }

            // 向群组内所有客户端广播消息
            synchronized (clients) {
                Iterator<ClientHandler> iterator = clients.iterator();
                while (iterator.hasNext()) {
                    ClientHandler client = iterator.next();
                    try {
                        client.sendMessage("[server] " + messageToBroadcast);
                    } catch (Exception e) {
                        // 客户端可能已断开连接
                        clients.remove(client);
                    }
                }
            }
        }
    }

    void sendServerMessageToGroup(String channelName, String message) {
        String group = normalizeChannelName(channelName);
        List<ClientHandler> clients = server.groups.get(group);
        if (clients == null || clients.isEmpty()) {
            server.log("频道不存在或当前无人在线: " + channelName + " (" + group + ")");
            return;
        }

        String messageToBroadcast = message;
        if (ChatServer.PUBLIC_CHANNEL_GROUP.equals(group) && server.messageGuard.containsForbiddenWords(message)) {
            server.log("服务器频道消息检测到违禁词，消息将被过滤: " + message);
            messageToBroadcast = server.messageGuard.filterForbiddenWords(message);
        }

        for (ClientHandler client : new ArrayList<>(clients)) {
            try {
                client.sendMessage("[server] " + messageToBroadcast);
            } catch (Exception e) {
                clients.remove(client);
            }
        }
        server.log("[server -> " + group + "] " + messageToBroadcast);
    }

    String normalizeChannelName(String channelName) {
        String name = channelName.trim();
        String mapped = server.accountGroups.get(name);
        if (mapped != null) {
            return mapped;
        }
        if (name.startsWith("group_")) {
            return name;
        }
        return "group_" + name;
    }

    void sendServerPrivateMessage(String username, String message) {
        if (server.messageGuard.isMessageTooLong(message)) {
            server.log("服务器私聊失败，消息超过" + ChatServer.MAX_MESSAGE_BYTES + "字节限制");
            return;
        }
        ClientHandler target = server.userManager.findClientHandlerByNickname(username);
        if (target == null) {
            server.log("服务器私聊失败，用户不在线: " + username);
            server.ui.refreshOnlineUsersPanel();
            return;
        }
        target.sendMessage("/p2p_msg|" + ChatServer.SERVER_P2P_NAME + "|" + ChatServer.SERVER_P2P_PASSWORD + "|" + message);
        server.log("[server -> " + username + "] " + message);
    }
}
