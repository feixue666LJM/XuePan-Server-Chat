package feixue.chat.server.com;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

// 用户管理功能：在线用户、封禁、禁言、点对点聊天密码、按昵称查找客户端
class UserManager {
    private final ChatServer server;

    UserManager(ChatServer server) {
        this.server = server;
    }

    // 禁止用户
    void banUser(String username) {
        server.bannedUsers.add(username);
        saveBannedUsers(); // 保存到文件

        // 断开被禁止用户的连接
        for (Map.Entry<String, List<ClientHandler>> entry : server.groups.entrySet()) {
            List<ClientHandler> clients = entry.getValue();
            synchronized (clients) {
                // groups are CopyOnWriteArrayList instances; their iterators are
                // intentionally read-only, so remove from the list itself.
                for (ClientHandler client : new ArrayList<>(clients)) {
                    if (username.equals(client.getNickname())) {
                        try {
                            client.sendMessage("您已被服务器禁止");
                            client.socket.close();
                        } catch (Exception e) {
                            // 忽略异常
                        }
                        clients.remove(client);
                    }
                }
            }
        }
    }

    // 解除禁止用户
    void unbanUser(String username) {
        server.bannedUsers.remove(username);
        saveBannedUsers(); // 保存到文件
    }

    // 保存被禁止的用户到文件
    void saveBannedUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("ban.txt"))) {
            for (String user : server.bannedUsers) {
                writer.println(user);
            }
        } catch (IOException e) {
            server.log("保存禁止用户列表失败: " + e.getMessage());
        }
    }

    // 加载被禁止的用户列表
    void loadBannedUsers() {
        File banFile = new File("ban.txt");
        if (!banFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(banFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String user = line.trim();
                if (!user.isEmpty()) {
                    server.bannedUsers.add(user);
                }
            }
        } catch (IOException e) {
            server.log("加载禁止用户列表失败: " + e.getMessage());
        }
    }

    void loadMutedUsers() {
        Path mutedPath = Paths.get(ChatServer.MUTED_USERS_FILE);
        if (!Files.exists(mutedPath)) {
            saveMutedUsers();
            return;
        }

        long now = System.currentTimeMillis();
        boolean needsRewrite = false;
        try (BufferedReader reader = Files.newBufferedReader(mutedPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2 || parts[1].trim().isEmpty()) {
                    needsRewrite = true;
                    continue;
                }
                try {
                    long expiresAt = Long.parseLong(parts[0]);
                    if (expiresAt > now) {
                        server.mutedUsers.put(parts[1], expiresAt);
                    } else {
                        needsRewrite = true;
                    }
                } catch (NumberFormatException e) {
                    needsRewrite = true;
                }
            }
            server.log("从 " + ChatServer.MUTED_USERS_FILE + " 加载禁言用户，共 " + server.mutedUsers.size() + " 个");
        } catch (IOException e) {
            server.log("加载禁言用户列表失败: " + e.getMessage());
            return;
        }

        if (needsRewrite) {
            saveMutedUsers();
        }
    }

    void saveMutedUsers() {
        Path mutedPath = Paths.get(ChatServer.MUTED_USERS_FILE);
        Path tempPath = Paths.get(ChatServer.MUTED_USERS_FILE + ".tmp");
        List<String> lines = new ArrayList<>();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(server.mutedUsers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Long> entry : entries) {
            lines.add(entry.getValue() + "\t" + entry.getKey());
        }

        try {
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, mutedPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempPath, mutedPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            server.log("保存禁言用户列表失败: " + e.getMessage());
        }
    }

    // 加载在线用户列表
    void loadOnlineUsers() {
        File userFile = new File("user.txt");
        if (!userFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(userFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String user = line.trim();
                if (!user.isEmpty()) {
                    server.onlineUsers.add(user);
                }
            }
        } catch (IOException e) {
            server.log("加载在线用户列表失败: " + e.getMessage());
        }
    }

    // 保存在线用户列表
    void saveOnlineUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("user.txt"))) {
            for (String user : server.onlineUsers) {
                writer.println(user);
            }
        } catch (IOException e) {
            server.log("保存在线用户列表失败: " + e.getMessage());
        }
    }

    // 生成随机的5位数字点对点聊天密码
    String generateP2PPassword() {
        Random rand = new Random();
        String password;
        do {
            password = String.valueOf(10000 + rand.nextInt(90000)); // 10000-99999
        } while (server.passwordToUser.containsKey(password));
        return password;
    }

    // 广播在线用户列表给所有客户端
    void broadcastOnlineUsers() {
        if (server.onlineUsers.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String user : server.onlineUsers) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(user);
        }
        String userList = sb.toString();
        // 遍历所有群组中的客户端
        for (List<ClientHandler> clients : server.groups.values()) {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client.versionChecked) {
                        client.sendMessage("/online_users|" + userList);
                    }
                }
            }
        }
    }

    // 检查用户是否在线
    boolean isUserOnline(String username) {
        return server.onlineUsers.contains(username);
    }

    // 从在线用户列表中移除用户
    void removeOnlineUser(String username) {
        if (server.onlineUsers.remove(username)) {
            saveOnlineUsers(); // 更新文件
        }
        server.ui.refreshOnlineUsersPanel();
    }

    // 添加用户到在线用户列表
    void addOnlineUser(String username) {
        if (server.onlineUsers.add(username)) {
            saveOnlineUsers(); // 更新文件
        }
        server.ui.refreshOnlineUsersPanel();
    }

    ClientHandler findClientHandlerByNickname(String username) {
        ClientHandler handler = server.userHandlers.get(username);
        if (handler != null) {
            return handler;
        }
        for (List<ClientHandler> clients : server.groups.values()) {
            for (ClientHandler client : new ArrayList<>(clients)) {
                if (username.equals(client.getNickname())) {
                    return client;
                }
            }
        }
        return null;
    }

    boolean isUserMuted(String username) {
        if (username == null) {
            return false;
        }
        Long expiresAt = server.mutedUsers.get(username);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            if (server.mutedUsers.remove(username, expiresAt)) {
                saveMutedUsers();
                server.log("用户禁言已到期: " + username);
            }
            return false;
        }
        return true;
    }

    void cleanupExpiredMutedUsers() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Long> entry : server.mutedUsers.entrySet()) {
            if (entry.getValue() <= now && server.mutedUsers.remove(entry.getKey(), entry.getValue())) {
                server.log("用户禁言已到期: " + entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            saveMutedUsers();
        }
    }

    String getMuteRemainingText(String username) {
        Long expiresAt = server.mutedUsers.get(username);
        if (expiresAt == null) {
            return "0分钟";
        }
        long remainingMillis = Math.max(0, expiresAt - System.currentTimeMillis());
        long totalMinutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(remainingMillis));
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0 && minutes > 0) {
            return hours + "小时" + minutes + "分钟";
        }
        if (hours > 0) {
            return hours + "小时";
        }
        return minutes + "分钟";
    }

    void kickUser(String username) {
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler == null) {
            server.log("踢出失败，用户不在线: " + username);
            server.ui.refreshOnlineUsersPanel();
            return;
        }
        try {
            handler.sendMessage("您已被服务器强制踢出");
            handler.closeConnection();
            server.log("服务器: 已强制踢出用户 " + username);
        } catch (IOException e) {
            server.log("踢出用户失败: " + username + "，" + e.getMessage());
        }
    }

    // A web-pan captcha failure applies to every connection using the same user ID.
    void disconnectAllConnectionsForUser(String username) {
        if (username == null || username.isEmpty()) return;
        int disconnected = 0;
        for (ClientHandler handler : new ArrayList<>(server.allClientHandlers)) {
            if (!username.equals(handler.getNickname()) && !username.equals(handler.getWebUserId())) continue;
            try {
                handler.sendMessage("下载验证失败，您已被服务器强制踢出");
                handler.closeConnection();
            } catch (IOException ignored) {
                try { handler.socket.close(); } catch (IOException ignoredAgain) { }
            }
            disconnected++;
        }
        server.log("下载验证失败，已断开用户 " + username + " 的 " + disconnected + " 个连接");
    }

    void muteUser(String username, double hours) {
        long durationMillis = Math.max(1L, Math.round(hours * 60 * 60 * 1000));
        long expiresAt = System.currentTimeMillis() + durationMillis;
        server.mutedUsers.put(username, expiresAt);
        saveMutedUsers();
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler != null) {
            server.voiceManager.leaveVoiceRoom(handler, true);
            server.voiceManager.endP2PVoiceCall(username);
            server.voiceManager.clearPendingP2PVoiceRequests(username);
            if (username.equals(server.serverP2PVoicePeer)) {
                server.voiceManager.stopServerP2PVoiceSession(true);
            }
            synchronized (server.serverP2PVoiceLock) {
                if (username.equals(server.serverP2PVoicePendingUser)) {
                    server.serverP2PVoicePendingUser = null;
                    server.serverP2PVoicePendingTime = 0;
                    handler.sendMessage("/live_p2p_cancelled|" + ChatServer.SERVER_P2P_NAME);
                }
            }
            handler.sendMessage("您已被服务器禁言 " + formatHours(hours) + " 小时，只能查看其他用户消息");
        }
        server.log("服务器: 已禁言用户 " + username + "，时长 " + formatHours(hours) + " 小时");
    }

    void unmuteUser(String username) {
        Long removed = server.mutedUsers.remove(username);
        if (removed == null) {
            server.log("解除禁言失败，未找到禁言用户: " + username);
            return;
        }
        saveMutedUsers();
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler != null) {
            handler.sendMessage("服务器已解除您的禁言");
        }
        server.log("服务器: 已解除用户禁言 " + username);
    }

    boolean isReservedServerUsername(String username) {
        return username != null && ChatServer.SERVER_P2P_NAME.equalsIgnoreCase(username.trim());
    }

    void notifyMutedStatus(ClientHandler handler) {
        String username = handler.getNickname();
        if (isUserMuted(username)) {
            handler.sendMessage("您当前仍被服务器禁言，剩余" + getMuteRemainingText(username)
                    + "，只能查看其他用户消息");
        }
    }

    String formatHours(double hours) {
        if (Math.floor(hours) == hours) {
            return String.valueOf((long) hours);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", hours);
    }
}
