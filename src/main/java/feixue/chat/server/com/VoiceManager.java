package feixue.chat.server.com;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.SwingUtilities;

// 语音功能管理：频道实时语音（含服务器端加入频道）、服务器私聊语音、混音任务
class VoiceManager {
    private final ChatServer server;

    VoiceManager(ChatServer server) {
        this.server = server;
    }

    Set<String> knownVoiceChannels() {
        Set<String> channels = new LinkedHashSet<>();
        channels.add(ChatServer.PUBLIC_CHANNEL_GROUP);
        channels.addAll(server.configuredChannelGroups);
        return channels;
    }

    void refreshVoiceChannelPanel() {
        if (server.ui.voiceChannelsPanel == null) {
            return;
        }
        if (!server.voiceChannelRefreshPending.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            server.voiceChannelRefreshPending.set(false);
            Set<String> channels = knownVoiceChannels();
            Iterator<Map.Entry<String, VoiceChannelRow>> rowIterator = server.ui.voiceChannelRows.entrySet().iterator();
            while (rowIterator.hasNext()) {
                Map.Entry<String, VoiceChannelRow> entry = rowIterator.next();
                if (!channels.contains(entry.getKey())) {
                    server.ui.voiceChannelsPanel.remove(entry.getValue().panel);
                    rowIterator.remove();
                    server.voiceChannelEnabled.remove(entry.getKey());
                    server.voiceChannelVolumeGain.remove(entry.getKey());
                }
            }
            for (String group : channels) {
                server.voiceChannelEnabled.putIfAbsent(group, true);
                server.voiceChannelVolumeGain.putIfAbsent(group, ChatServer.MIN_VOICE_VOLUME_GAIN);
                VoiceChannelRow row = server.ui.voiceChannelRows.get(group);
                if (row == null) {
                    row = new VoiceChannelRow(group);
                    server.ui.voiceChannelRows.put(group, row);
                    VoiceChannelRow finalRow = row;
                    row.enabledButton.addActionListener(e ->
                            setVoiceChannelEnabled(group, finalRow.enabledButton.isSelected()));
                    row.volumeBox.addActionListener(e ->
                            setVoiceChannelVolumeGain(group, finalRow.volumeBox.getSelectedIndex() + 1));
                    row.joinButton.addActionListener(e -> toggleServerVoiceChannel(group));
                    server.ui.voiceChannelsPanel.add(row.panel);
                }
                boolean enabled = server.voiceChannelEnabled.getOrDefault(group, true);
                int volumeGain = server.voiceChannelVolumeGain.getOrDefault(group, ChatServer.MIN_VOICE_VOLUME_GAIN);
                boolean serverJoined = group.equals(server.serverVoiceGroup);
                Set<ClientHandler> members = server.voiceRooms.get(group);
                int memberCount = members == null ? 0 : members.size();
                if (serverJoined) {
                    memberCount++;
                }
                boolean running = server.serverSocket != null && !server.serverSocket.isClosed() && server.isRunning;
                row.enabledButton.setSelected(enabled);
                row.enabledButton.setText(enabled ? "关闭" : "开启");
                row.volumeBox.setSelectedIndex(volumeGain - 1);
                row.statusLabel.setText((enabled ? "已开启" : "已关闭") + " | "
                        + (running ? "运行中" : "未启动") + " | 成员 " + memberCount
                        + " | 音量 x" + volumeGain
                        + (serverJoined ? " | 服务器已加入" : ""));
                row.joinButton.setText(serverJoined ? "服务器退出" : "服务器加入");
                row.joinButton.setEnabled(running && enabled || serverJoined);
            }
            server.ui.voiceChannelsPanel.revalidate();
            server.ui.voiceChannelsPanel.repaint();
            if (server.ui.voiceOverviewLabel != null) {
                server.ui.voiceOverviewLabel.setText("频道 " + channels.size() + " 个 | 开启 "
                        + channels.stream().filter(g -> server.voiceChannelEnabled.getOrDefault(g, true)).count()
                        + " 个 | 服务器当前加入: " + (server.serverVoiceGroup == null ? "无" : server.serverVoiceGroup));
            }
        });
    }

    void setVoiceChannelEnabled(String group, boolean enabled) {
        server.voiceChannelEnabled.put(group, enabled);
        if (!enabled) {
            Set<ClientHandler> members = server.voiceRooms.get(group);
            if (members != null) {
                for (ClientHandler member : new ArrayList<>(members)) {
                    leaveVoiceRoom(member, true);
                    member.sendMessage("/live_group_disabled|" + group);
                }
            }
            if (group.equals(server.serverVoiceGroup)) {
                stopServerVoiceSession();
            }
        }
        refreshVoiceChannelPanel();
    }

    void setVoiceChannelVolumeGain(String group, int volumeGain) {
        int clampedGain = Math.max(ChatServer.MIN_VOICE_VOLUME_GAIN,
                Math.min(ChatServer.MAX_VOICE_VOLUME_GAIN, volumeGain));
        Integer previousGain = server.voiceChannelVolumeGain.put(group, clampedGain);
        if (previousGain == null || previousGain != clampedGain) {
            server.log("语音频道音量已设置: " + group + " -> x" + clampedGain);
            refreshVoiceChannelPanel();
        }
    }

    void toggleServerVoiceChannel(String group) {
        if (group.equals(server.serverVoiceGroup)) {
            stopServerVoiceSession();
            return;
        }
        if (!server.voiceChannelEnabled.getOrDefault(group, true)) {
            server.log("语音频道已关闭，无法加入: " + group);
            return;
        }
        if (server.serverSocket == null || server.serverSocket.isClosed() || !server.isRunning) {
            server.log("请先启动服务器，再加入语音频道");
            return;
        }
        if (server.serverP2PVoicePeer != null || server.serverP2PVoicePendingUser != null || server.serverP2PVoiceStarting) {
            server.log("请先结束服务器私聊语音，再加入频道语音");
            return;
        }
        stopServerVoiceSession();
        startServerVoiceSession(group);
    }

    void startServerVoiceSession(String group) {
        synchronized (server.serverVoiceLock) {
            if (server.serverVoiceGroup != null || server.serverVoiceStarting) {
                return;
            }
            server.serverVoiceStarting = true;
        }
        new Thread(() -> {
            TargetDataLine target = null;
            SourceDataLine source = null;
            try {
                target = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, ChatServer.SERVER_VOICE_FORMAT));
                source = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, ChatServer.SERVER_VOICE_FORMAT));
                target.open(ChatServer.SERVER_VOICE_FORMAT, ChatServer.SERVER_VOICE_CHUNK_BYTES * 8);
                source.open(ChatServer.SERVER_VOICE_FORMAT, ChatServer.SERVER_VOICE_CHUNK_BYTES * 8);
                target.start();
                source.start();
                synchronized (server.serverVoiceLock) {
                    if (!server.serverVoiceStarting) {
                        target.close();
                        source.close();
                        return;
                    }
                    server.serverVoiceTargetLine = target;
                    server.serverVoiceSourceLine = source;
                    server.serverVoiceGroup = group;
                    server.serverVoiceStarting = false;
                }
                server.serverVoicePlaybackQueue.clear();
                startServerVoiceCapture(target, group);
                startServerVoicePlayback(source);
                refreshVoiceChannelPanel();
                server.log("服务器已加入语音频道: " + group);
            } catch (Exception ex) {
                if (target != null) target.close();
                if (source != null) source.close();
                synchronized (server.serverVoiceLock) {
                    server.serverVoiceStarting = false;
                    server.serverVoiceGroup = null;
                }
                refreshVoiceChannelPanel();
                server.log("服务器加入语音频道失败: " + ex.getMessage());
            }
        }, "ServerVoiceStarter").start();
    }

    void startServerVoiceCapture(TargetDataLine target, String group) {
        server.serverVoiceCaptureThread = new Thread(() -> {
            byte[] buffer = new byte[ChatServer.SERVER_VOICE_CHUNK_BYTES];
            while (group.equals(server.serverVoiceGroup) && !Thread.currentThread().isInterrupted()) {
                int count = target.read(buffer, 0, buffer.length);
                if (count > 0 && group.equals(server.serverVoiceGroup)) {
                    byte[] frame = new byte[ChatServer.SERVER_VOICE_CHUNK_BYTES];
                    System.arraycopy(buffer, 0, frame, 0, Math.min(count, frame.length));
                    BlockingQueue<byte[]> queue = server.serverVoiceAudioQueues.computeIfAbsent(group,
                            key -> new ArrayBlockingQueue<>(ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY));
                    offerAudioFrame(queue, frame, ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY);
                }
            }
        }, "ServerVoiceCapture");
        server.serverVoiceCaptureThread.setDaemon(true);
        server.serverVoiceCaptureThread.start();
    }

    void startServerVoicePlayback(SourceDataLine source) {
        server.serverVoicePlaybackThread = new Thread(() -> {
            while (server.serverVoiceGroup != null && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = server.serverVoicePlaybackQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        source.write(frame, 0, frame.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ServerVoicePlayback");
        server.serverVoicePlaybackThread.setDaemon(true);
        server.serverVoicePlaybackThread.start();
    }

    void stopServerVoiceSession() {
        TargetDataLine target;
        SourceDataLine source;
        synchronized (server.serverVoiceLock) {
            server.serverVoiceStarting = false;
            server.serverVoiceGroup = null;
            target = server.serverVoiceTargetLine;
            source = server.serverVoiceSourceLine;
            server.serverVoiceTargetLine = null;
            server.serverVoiceSourceLine = null;
        }
        if (target != null) {
            target.stop();
            target.close();
        }
        if (source != null) {
            source.stop();
            source.flush();
            source.close();
        }
        if (server.serverVoiceCaptureThread != null) server.serverVoiceCaptureThread.interrupt();
        if (server.serverVoicePlaybackThread != null) server.serverVoicePlaybackThread.interrupt();
        server.serverVoicePlaybackQueue.clear();
        server.serverVoiceAudioQueues.clear();
        refreshVoiceChannelPanel();
    }

    void requestServerP2PVoice(String username) {
        if (server.serverSocket == null || server.serverSocket.isClosed() || !server.isRunning) {
            server.log("请先启动服务器，再发起私聊语音");
            return;
        }
        ClientHandler target = server.userManager.findClientHandlerByNickname(username);
        if (target == null) {
            server.log("服务器私聊语音失败，用户不在线: " + username);
            server.ui.refreshOnlineUsersPanel();
            return;
        }
        if (server.userManager.isUserMuted(username)) {
            server.log("服务器私聊语音失败，用户当前被禁言: " + username);
            return;
        }
        if (isVoiceRoomMember(target) || server.activeP2PVoicePeers.containsKey(username)
                || server.pendingP2PVoiceRequests.containsKey(username)
                || server.pendingP2PVoiceRequests.containsValue(username)) {
            server.log("服务器私聊语音失败，对方正在语音或有待处理申请: " + username);
            return;
        }
        synchronized (server.serverP2PVoiceLock) {
            if (server.serverP2PVoicePeer != null || server.serverP2PVoicePendingUser != null || server.serverP2PVoiceStarting) {
                server.log("服务器已有私聊语音会话或申请");
                return;
            }
            server.serverP2PVoicePendingUser = username;
            server.serverP2PVoicePendingTime = System.currentTimeMillis();
        }
        stopServerVoiceSession();
        target.sendMessage("/live_p2p_request|" + ChatServer.SERVER_P2P_NAME + "|" + ChatServer.SERVER_P2P_PASSWORD);
        server.log("服务器已向 " + username + " 发起私聊语音申请");
    }

    boolean handleServerP2PVoiceAccept(ClientHandler targetHandler, String requesterPassword) {
        if (!ChatServer.SERVER_P2P_PASSWORD.equals(requesterPassword)) {
            return false;
        }
        String username = targetHandler.getNickname();
        synchronized (server.serverP2PVoiceLock) {
            if (!username.equals(server.serverP2PVoicePendingUser)) {
                targetHandler.sendMessage("/live_voice_error|语音申请已失效");
                return true;
            }
            if (isVoiceRoomMember(targetHandler) || server.activeP2PVoicePeers.containsKey(username)
                    || server.pendingP2PVoiceRequests.containsKey(username)
                    || server.pendingP2PVoiceRequests.containsValue(username)) {
                server.serverP2PVoicePendingUser = null;
                server.serverP2PVoicePendingTime = 0;
                targetHandler.sendMessage("/live_voice_error|您当前已有语音会话或待处理申请");
                return true;
            }
            if (server.serverP2PVoicePeer != null || server.serverP2PVoiceStarting) {
                targetHandler.sendMessage("/live_voice_error|服务器当前已有语音会话");
                return true;
            }
            server.serverP2PVoicePendingUser = null;
            server.serverP2PVoicePendingTime = 0;
            server.serverP2PVoiceStarting = true;
        }
        startServerP2PVoiceSession(targetHandler);
        return true;
    }

    boolean handleServerP2PVoiceReject(ClientHandler targetHandler, String requesterPassword) {
        if (!ChatServer.SERVER_P2P_PASSWORD.equals(requesterPassword)) {
            return false;
        }
        String username = targetHandler.getNickname();
        synchronized (server.serverP2PVoiceLock) {
            if (username.equals(server.serverP2PVoicePendingUser)) {
                server.serverP2PVoicePendingUser = null;
                server.serverP2PVoicePendingTime = 0;
                server.log("用户已拒绝服务器私聊语音申请: " + username);
            }
        }
        return true;
    }

    void startServerP2PVoiceSession(ClientHandler targetHandler) {
        String peer = targetHandler.getNickname();
        new Thread(() -> {
            TargetDataLine target = null;
            SourceDataLine source = null;
            try {
                target = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, ChatServer.SERVER_VOICE_FORMAT));
                source = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, ChatServer.SERVER_VOICE_FORMAT));
                target.open(ChatServer.SERVER_VOICE_FORMAT, ChatServer.SERVER_VOICE_CHUNK_BYTES * 8);
                source.open(ChatServer.SERVER_VOICE_FORMAT, ChatServer.SERVER_VOICE_CHUNK_BYTES * 8);
                target.start();
                source.start();
                synchronized (server.serverP2PVoiceLock) {
                    if (!server.serverP2PVoiceStarting) {
                        target.close();
                        source.close();
                        return;
                    }
                    server.serverP2PVoiceTargetLine = target;
                    server.serverP2PVoiceSourceLine = source;
                    server.serverP2PVoicePeer = peer;
                    server.serverP2PVoiceStarting = false;
                }
                server.serverP2PVoicePlaybackQueue.clear();
                targetHandler.sendMessage("/live_p2p_started|" + ChatServer.SERVER_P2P_NAME + "|" + ChatServer.SERVER_P2P_PASSWORD);
                startServerP2PVoiceCapture(target, peer);
                startServerP2PVoicePlayback(source);
                server.log("服务器私聊语音已建立: " + ChatServer.SERVER_P2P_NAME + " <-> " + peer);
            } catch (Exception ex) {
                if (target != null) target.close();
                if (source != null) source.close();
                synchronized (server.serverP2PVoiceLock) {
                    server.serverP2PVoiceStarting = false;
                    server.serverP2PVoicePeer = null;
                }
                targetHandler.sendMessage("/live_p2p_rejected|" + ChatServer.SERVER_P2P_NAME + "|服务器语音设备启动失败");
                server.log("服务器私聊语音启动失败: " + ex.getMessage());
            }
        }, "ServerP2PVoiceStarter").start();
    }

    void startServerP2PVoiceCapture(TargetDataLine target, String peer) {
        server.serverP2PVoiceCaptureThread = new Thread(() -> {
            byte[] buffer = new byte[ChatServer.SERVER_VOICE_CHUNK_BYTES];
            while (peer.equals(server.serverP2PVoicePeer) && !Thread.currentThread().isInterrupted()) {
                int count = target.read(buffer, 0, buffer.length);
                if (count <= 0 || !peer.equals(server.serverP2PVoicePeer)) {
                    continue;
                }
                byte[] frame = new byte[ChatServer.SERVER_VOICE_CHUNK_BYTES];
                System.arraycopy(buffer, 0, frame, 0, Math.min(count, frame.length));
                ClientHandler peerHandler = server.userManager.findClientHandlerByNickname(peer);
                if (peerHandler == null) {
                    stopServerP2PVoiceSession(false);
                    break;
                }
                peerHandler.sendMessage("/live_p2p_audio|" + ChatServer.SERVER_P2P_NAME + "|"
                        + Base64.getEncoder().encodeToString(frame));
            }
        }, "ServerP2PVoiceCapture");
        server.serverP2PVoiceCaptureThread.setDaemon(true);
        server.serverP2PVoiceCaptureThread.start();
    }

    void startServerP2PVoicePlayback(SourceDataLine source) {
        server.serverP2PVoicePlaybackThread = new Thread(() -> {
            while (server.serverP2PVoicePeer != null && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = server.serverP2PVoicePlaybackQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        source.write(frame, 0, frame.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ServerP2PVoicePlayback");
        server.serverP2PVoicePlaybackThread.setDaemon(true);
        server.serverP2PVoicePlaybackThread.start();
    }

    boolean handleServerP2PVoiceAudio(String username, String audioData) {
        if (!username.equals(server.serverP2PVoicePeer)) {
            return false;
        }
        if (audioData.isEmpty() || audioData.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
            return true;
        }
        try {
            byte[] audioFrame = Base64.getDecoder().decode(audioData);
            if (audioFrame.length == 0 || audioFrame.length > ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                return true;
            }
            if (audioFrame.length != ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                audioFrame = java.util.Arrays.copyOf(audioFrame, ChatServer.LIVE_AUDIO_CHUNK_BYTES);
            }
            offerAudioFrame(server.serverP2PVoicePlaybackQueue, audioFrame, ChatServer.GROUP_AUDIO_MAX_PLAYBACK_FRAMES);
        } catch (IllegalArgumentException ignored) {
            // 忽略无效Base64音频帧
        }
        return true;
    }

    void stopServerP2PVoiceSession(boolean notifyPeer) {
        String peer;
        TargetDataLine target;
        SourceDataLine source;
        synchronized (server.serverP2PVoiceLock) {
            server.serverP2PVoiceStarting = false;
            peer = server.serverP2PVoicePeer;
            server.serverP2PVoicePeer = null;
            target = server.serverP2PVoiceTargetLine;
            source = server.serverP2PVoiceSourceLine;
            server.serverP2PVoiceTargetLine = null;
            server.serverP2PVoiceSourceLine = null;
        }
        if (target != null) {
            target.stop();
            target.close();
        }
        if (source != null) {
            source.stop();
            source.flush();
            source.close();
        }
        if (server.serverP2PVoiceCaptureThread != null) server.serverP2PVoiceCaptureThread.interrupt();
        if (server.serverP2PVoicePlaybackThread != null) server.serverP2PVoicePlaybackThread.interrupt();
        server.serverP2PVoicePlaybackQueue.clear();
        if (notifyPeer && peer != null) {
            ClientHandler peerHandler = server.userManager.findClientHandlerByNickname(peer);
            if (peerHandler != null) {
                peerHandler.sendMessage("/live_p2p_ended|" + ChatServer.SERVER_P2P_NAME);
            }
        }
        if (peer != null) {
            server.log("服务器私聊语音已结束: " + ChatServer.SERVER_P2P_NAME + " <-> " + peer);
        }
    }

    void cleanupExpiredServerP2PVoiceRequest() {
        String pendingUser;
        synchronized (server.serverP2PVoiceLock) {
            if (server.serverP2PVoicePendingUser == null
                    || System.currentTimeMillis() - server.serverP2PVoicePendingTime <= ChatServer.P2P_VOICE_REQUEST_TIMEOUT) {
                return;
            }
            pendingUser = server.serverP2PVoicePendingUser;
            server.serverP2PVoicePendingUser = null;
            server.serverP2PVoicePendingTime = 0;
        }
        ClientHandler target = server.userManager.findClientHandlerByNickname(pendingUser);
        if (target != null) {
            target.sendMessage("/live_p2p_cancelled|" + ChatServer.SERVER_P2P_NAME);
        }
        server.log("服务器私聊语音申请已超时: " + pendingUser);
    }

    void handleServerP2PClientUnavailable(String username) {
        if (username == null) {
            return;
        }
        if (username.equals(server.serverP2PVoicePeer)) {
            stopServerP2PVoiceSession(false);
        }
        synchronized (server.serverP2PVoiceLock) {
            if (username.equals(server.serverP2PVoicePendingUser)) {
                server.serverP2PVoicePendingUser = null;
                server.serverP2PVoicePendingTime = 0;
                server.log("服务器私聊语音申请已取消，用户离线: " + username);
            }
        }
    }

    boolean isVoiceRoomMember(ClientHandler client) {
        if (client == null || client.group == null) {
            return false;
        }
        Set<ClientHandler> members = server.voiceRooms.get(client.group);
        return members != null && members.contains(client);
    }

    void broadcastVoiceRoomMemberCount(String group) {
        Set<ClientHandler> members = server.voiceRooms.get(group);
        if (members == null) {
            return;
        }
        String message = "/live_group_members|" + members.size();
        for (ClientHandler member : members) {
            member.sendMessage(message);
        }
        refreshVoiceChannelPanel();
    }

    void leaveVoiceRoom(ClientHandler client, boolean notifySelf) {
        if (client == null || client.group == null) {
            return;
        }
        Set<ClientHandler> members = server.voiceRooms.get(client.group);
        if (members == null || !members.remove(client)) {
            return;
        }
        if (members.isEmpty()) {
            server.voiceRooms.remove(client.group, members);
            server.voiceRoomAudioQueues.remove(client.group);
        } else {
            Map<ClientHandler, BlockingQueue<byte[]>> queues = server.voiceRoomAudioQueues.get(client.group);
            if (queues != null) {
                queues.remove(client);
            }
            broadcastVoiceRoomMemberCount(client.group);
        }
        if (notifySelf) {
            client.sendMessage("/live_group_left");
        }
        server.log("用户 " + client.nickname + " 退出频道语音: " + client.group);
        refreshVoiceChannelPanel();
    }

    void endP2PVoiceCall(String username) {
        if (username == null) {
            return;
        }
        String peer = server.activeP2PVoicePeers.remove(username);
        if (peer == null) {
            return;
        }
        server.activeP2PVoicePeers.remove(peer, username);
        ClientHandler userHandler = server.userHandlers.get(username);
        ClientHandler peerHandler = server.userHandlers.get(peer);
        if (userHandler != null) {
            userHandler.sendMessage("/live_p2p_ended|" + peer);
        }
        if (peerHandler != null) {
            peerHandler.sendMessage("/live_p2p_ended|" + username);
        }
        server.log("私聊语音已结束: " + username + " <-> " + peer);
    }

    void clearPendingP2PVoiceRequests(String username) {
        if (username == null) {
            return;
        }
        String requester = server.pendingP2PVoiceRequests.remove(username);
        server.pendingP2PVoiceRequestTimes.remove(username);
        if (requester != null) {
            ClientHandler requesterHandler = server.userHandlers.get(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage("/live_p2p_rejected|" + username + "|用户已离线");
            }
        }
        for (Map.Entry<String, String> entry : server.pendingP2PVoiceRequests.entrySet()) {
            if (username.equals(entry.getValue()) && server.pendingP2PVoiceRequests.remove(entry.getKey(), username)) {
                server.pendingP2PVoiceRequestTimes.remove(entry.getKey());
                ClientHandler targetHandler = server.userHandlers.get(entry.getKey());
                if (targetHandler != null) {
                    targetHandler.sendMessage("/live_p2p_cancelled|" + username);
                }
            }
        }
    }

    void cleanupExpiredP2PVoiceRequests() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : server.pendingP2PVoiceRequestTimes.entrySet()) {
            String target = entry.getKey();
            Long createdAt = entry.getValue();
            if (createdAt == null || now - createdAt < ChatServer.P2P_VOICE_REQUEST_TIMEOUT) {
                continue;
            }
            if (!server.pendingP2PVoiceRequestTimes.remove(target, createdAt)) {
                continue;
            }
            String requester = server.pendingP2PVoiceRequests.remove(target);
            if (requester == null) {
                continue;
            }
            ClientHandler requesterHandler = server.userHandlers.get(requester);
            ClientHandler targetHandler = server.userHandlers.get(target);
            if (requesterHandler != null) {
                requesterHandler.sendMessage("/live_p2p_rejected|" + target + "|语音申请已超时");
            }
            if (targetHandler != null) {
                targetHandler.sendMessage("/live_p2p_cancelled|" + requester);
            }
        }
    }

    void startVoiceMixerTask() {
        final ServerSocket mixerServerSocket = server.serverSocket;
        Thread mixerThread = new Thread(() -> {
            final long frameNanos = TimeUnit.MILLISECONDS.toNanos(20);
            long nextFrameAt = System.nanoTime();
            while (server.isRunning && server.serverSocket == mixerServerSocket
                    && mixerServerSocket != null && !mixerServerSocket.isClosed()) {
                mixVoiceRoomFrames();
                nextFrameAt += frameNanos;
                long sleepNanos = nextFrameAt - System.nanoTime();
                try {
                    if (sleepNanos > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleepNanos);
                    } else if (sleepNanos < -frameNanos * 3) {
                        nextFrameAt = System.nanoTime();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "VoiceRoomMixer");
        mixerThread.setDaemon(true);
        mixerThread.start();
    }

    void mixVoiceRoomFrames() {
        for (Map.Entry<String, Set<ClientHandler>> roomEntry : server.voiceRooms.entrySet()) {
            String group = roomEntry.getKey();
            int volumeGain = server.voiceChannelVolumeGain.getOrDefault(group, ChatServer.MIN_VOICE_VOLUME_GAIN);
            Set<ClientHandler> members = roomEntry.getValue();
            if (members == null || members.isEmpty()) {
                continue;
            }

            Map<ClientHandler, byte[]> frames = new HashMap<>();
            Map<ClientHandler, BlockingQueue<byte[]>> senderQueues = server.voiceRoomAudioQueues.get(group);
            if (senderQueues != null) {
                for (Map.Entry<ClientHandler, BlockingQueue<byte[]>> entry : senderQueues.entrySet()) {
                    ClientHandler sender = entry.getKey();
                    if (!members.contains(sender)) {
                        senderQueues.remove(sender, entry.getValue());
                        continue;
                    }
                    byte[] frame = pollMixerFrame(entry.getValue());
                    if (frame != null) {
                        frames.put(sender, frame);
                    }
                }
            }

            BlockingQueue<byte[]> serverQueue = server.serverVoiceAudioQueues.get(group);
            byte[] serverFrame = pollMixerFrame(serverQueue);
            if (frames.isEmpty() && serverFrame == null) {
                continue;
            }
            byte[] roomMix = mixPcmFrames(frames, null, serverFrame, volumeGain);
            String roomMixMessage = roomMix == null ? null : "/live_group_audio|混音|"
                    + Base64.getEncoder().encodeToString(roomMix);
            for (ClientHandler receiver : members) {
                byte[] ownFrame = frames.get(receiver);
                if (ownFrame == null) {
                    if (roomMixMessage != null) {
                        receiver.sendMessage(roomMixMessage);
                    }
                    continue;
                }
                byte[] mixWithoutSelf = mixPcmFrames(frames, receiver, serverFrame, volumeGain);
                if (mixWithoutSelf != null) {
                    receiver.sendMessage("/live_group_audio|混音|"
                            + Base64.getEncoder().encodeToString(mixWithoutSelf));
                }
            }
            if (group.equals(server.serverVoiceGroup)) {
                byte[] serverPlayback = mixPcmFrames(frames, null, null, volumeGain);
                if (serverPlayback != null) {
                    offerAudioFrame(server.serverVoicePlaybackQueue, serverPlayback, ChatServer.GROUP_AUDIO_MAX_PLAYBACK_FRAMES);
                }
            }
        }
    }

    byte[] pollMixerFrame(BlockingQueue<byte[]> queue) {
        if (queue == null) {
            return null;
        }
        while (queue.size() > ChatServer.GROUP_AUDIO_TARGET_BUFFER_FRAMES) {
            queue.poll();
        }
        return queue.poll();
    }

    void offerAudioFrame(BlockingQueue<byte[]> queue, byte[] frame, int maxFrames) {
        if (queue == null || frame == null) {
            return;
        }
        while (queue.size() >= maxFrames) {
            queue.poll();
        }
        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
    }

    byte[] mixPcmFrames(Map<ClientHandler, byte[]> frames, ClientHandler excludedSender,
                        byte[] additionalFrame, int volumeGain) {
        int[] sums = new int[ChatServer.LIVE_AUDIO_CHUNK_BYTES / 2];
        boolean hasAudio = false;
        for (Map.Entry<ClientHandler, byte[]> entry : frames.entrySet()) {
            if (entry.getKey() == excludedSender) {
                continue;
            }
            byte[] frame = entry.getValue();
            if (frame == null || frame.length < 2) {
                continue;
            }
            hasAudio = true;
            int sampleCount = Math.min(sums.length, frame.length / 2);
            for (int i = 0; i < sampleCount; i++) {
                int low = frame[i * 2] & 0xff;
                int high = frame[i * 2 + 1] << 8;
                sums[i] += (short) (low | high);
            }
        }
        if (additionalFrame != null && additionalFrame.length >= 2) {
            hasAudio = true;
            int sampleCount = Math.min(sums.length, additionalFrame.length / 2);
            for (int i = 0; i < sampleCount; i++) {
                int low = additionalFrame[i * 2] & 0xff;
                int high = additionalFrame[i * 2 + 1] << 8;
                sums[i] += (short) (low | high);
            }
        }
        if (!hasAudio) {
            return null;
        }
        byte[] mixed = new byte[ChatServer.LIVE_AUDIO_CHUNK_BYTES];
        int clampedGain = Math.max(ChatServer.MIN_VOICE_VOLUME_GAIN,
                Math.min(ChatServer.MAX_VOICE_VOLUME_GAIN, volumeGain));
        for (int i = 0; i < sums.length; i++) {
            long amplifiedSample = (long) sums[i] * clampedGain;
            int sample = (int) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, amplifiedSample));
            mixed[i * 2] = (byte) (sample & 0xff);
            mixed[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return mixed;
    }
}
