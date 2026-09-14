package feixue.chat.server.com;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// 客户端消息处理线程（每个连接一个实例，负责登录、群聊、私聊、语音、图片等协议消息处理）
class ClientHandler implements Runnable {
    private final ChatServer server;
    Socket socket;
    private ClientTransport transport;
    private final boolean webClient;
    private final String clientIp;
    private final String tcpChallengeQuestion;
    private final int tcpChallengeAnswer;
    private boolean tcpVerified;
    String clientId;
    String nickname; // 客户端昵称
    String group;    // 客户端所属群组
    boolean versionChecked = false; // 版本验证标志
    private volatile boolean webVerified;
    private final WebPanService.Session webSession;
    private int webVerificationAttempts;
    private String webAuthorizedGroup;
    // The group authorised by a successful private-channel password login for this connection only.
    private String authorizedGroup;
    private boolean publicChannelAuthorized;
    private int p2pVerificationFailures;
    private long nextP2pVerificationAt;
    volatile long lastWebUserActivity = System.currentTimeMillis();
    private final BlockingQueue<String> liveAudioOutboundQueue = new ArrayBlockingQueue<>(12);
    private volatile boolean handlerActive = true;
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private final AtomicBoolean connectionAdmissionChecked = new AtomicBoolean(false);
    private Thread liveAudioWriterThread;

    public ClientHandler(ChatServer server, Socket socket, ClientTransport transport, boolean webClient) {
        this(server, socket, transport, webClient, null, null);
    }

    ClientHandler(ChatServer server, Socket socket, ClientTransport transport, boolean webClient,
                  WebPanService.Session webSession) {
        this(server, socket, transport, webClient, webSession, null);
    }

    ClientHandler(ChatServer server, Socket socket, ClientTransport transport, boolean webClient,
                  WebPanService.Session webSession, String reportedClientIp) {
        this.server = server;
        this.socket = socket;
        this.transport = transport;
        this.webClient = webClient;
        this.webSession = webSession;
        this.clientIp = reportedClientIp == null || reportedClientIp.isEmpty()
                ? socket.getInetAddress().getHostAddress() : reportedClientIp;
        if (webClient) {
            this.tcpChallengeQuestion = null;
            this.tcpChallengeAnswer = -1;
            this.tcpVerified = true;
        } else {
            int left = ThreadLocalRandom.current().nextInt(0, 11);
            int right = ThreadLocalRandom.current().nextInt(0, 11 - left);
            this.tcpChallengeQuestion = left + "+" + right;
            this.tcpChallengeAnswer = left + right;
            this.tcpVerified = false;
        }
        try {
            if (webClient && !server.webAccessEnabled) {
                transport.close();
                return;
            }
            socket.setTcpNoDelay(true);
            // 客户端ID仅作为内部连接标识与日志使用；网页端经过可信 Cloudflare 节点时，
            // IP 来自已验证的 CF-Connecting-IP，端口仍用于保证同 IP 的连接键唯一。
            // 不适用用户自定义ID的30字节长度限制（IPv6地址+端口会超过该限制导致误拒绝）。
            // 用户自定义ID（公共频道用户名、昵称）的长度限制在下方对应消息处理中单独校验。
            this.clientId = clientIp + ":" + socket.getPort();

            this.nickname = clientId; // 默认使用客户端ID作为昵称
            server.allClientHandlers.add(this);
            if (webClient) {
                server.webClientHandlers.add(this);
                server.ui.refreshWebControlState();
            }
            startLiveAudioWriter();
            server.clientNicknames.put(clientId, nickname); // 添加到昵称映射
            server.clientLastActiveTime.put(clientId, System.currentTimeMillis()); // 记录客户端连接时间

            if (!webClient) {
                sendMessage("/tcp_challenge|" + tcpChallengeQuestion);
            }
            new Thread(this).start(); // 启动处理线程
            if (webClient) {
                sendMessage("/web_challenge|" + server.http.encodeWebValue(server.webVerificationQuestion));
            }
        } catch (IOException e) {
            server.webPan.revoke(webSession);
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.http.closeQuietly(socket);
            server.log("初始化客户端连接失败: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            String message;
            while ((message = transport.readMessage()) != null && server.isRunning) {
                // 更新客户端最后活跃时间
                server.clientLastActiveTime.put(clientId, System.currentTimeMillis());
                if (!webClient && !tcpVerified) {
                    if (!handleTcpVerification(message)) {
                        continue;
                    }
                }
                if (webClient && !handleWebControlMessage(message)) {
                    continue;
                }
                if (requiresAuthenticatedChatSession(message) && !requireAuthenticatedChatSession(message)) {
                    continue;
                }
                if (webClient && isWebUserActivity(message)) {
                    lastWebUserActivity = System.currentTimeMillis();
                }
                if (isMutedForSending(message)) {
                    sendMessage("您已被服务器禁言，剩余" + server.userManager.getMuteRemainingText(nickname) + "，暂时无法发送内容");
                    continue;
                }
                
                // 检查是否是版本号信息
                if (message.startsWith("/version|")) {
                    String clientVersion = message.substring(9); // 提取版本号
                    if (server.messageGuard.isVersionCompatible(clientVersion)) {
                        versionChecked = true;
                        sendMessage("/version_check|success"); // 发送验证成功消息
                        server.log("客户端 " + clientId + " 版本验证成功: " + clientVersion);
                    } else {
                        sendMessage("/version_check|failed"); // 发送验证失败消息
                        server.log("客户端 " + clientId + " 版本验证失败: " + clientVersion);
                        // 关闭连接
                        break;
                    }
                }
                // 检查是否是登录验证消息
                else if (message.startsWith("/login|")) {
                    if (!requireVersionForLogin()) {
                        continue;
                    }
                    if (authorizedGroup != null || publicChannelAuthorized) {
                        sendMessage("/login_result|failure: 当前连接已完成频道验证");
                        continue;
                    }
                    // Count every private password attempt. This protects failed password guesses without
                    // treating TCP handshakes or /ping heartbeats as connection attempts.
                    if (!registerConnectionAdmissionAttempt()) break;
                    String[] parts = message.substring(7).split("\\|", -1);
                    String account, password;

                    // 支持两种登录格式：
                    // 1. 旧格式（电脑端）: /login|账户|密码
                    // 2. 新格式（手机端）: /login|账户|群组|密码
                    if (parts.length == 2) {
                        // 旧格式（电脑端）
                        account = parts[0];
                        password = parts[1];
                    } else if (parts.length == 3) {
                        // 新格式（手机端）
                        account = parts[0];
                        password = parts[2];
                    } else {
                        sendMessage("/login_result|failure"); // 格式错误
                        server.log("客户端 " + clientId + " 发送了格式错误的登录信息");
                        break;
                    }

                    if (server.userManager.isReservedServerUsername(account)) {
                        sendMessage("/login_result|failure: server 为服务器保留ID");
                        server.log("客户端 " + clientId + " 尝试使用服务器保留账户名: " + account);
                        break;
                    }

                    // 验证账户和密码
                    String correctPassword = server.accountPasswords.get(account);
                    if (correctPassword != null && correctPassword.equals(password)) {
                        String accountGroup = server.accountGroups.get(account);
                        if (accountGroup == null) {
                            sendMessage("/login_result|failure");
                            server.log("客户端 " + clientId + " 的账户未配置频道: " + account);
                            continue;
                        }
                        if (authorizedGroup != null && !authorizedGroup.equals(accountGroup)) {
                            sendMessage("/login_result|failure: 当前连接已验证其他频道");
                            server.log("客户端 " + clientId + " 尝试在同一连接切换登录频道");
                            break;
                        }
                        authorizedGroup = accountGroup;
                        if (webClient) {
                            webAuthorizedGroup = accountGroup;
                            // Bind the authenticated account for server-wide captcha kickout,
                            // even before the browser chooses a chat nickname.
                            server.webPan.bindUser(webSession, account);
                        }
                        sendMessage("/login_result|success"); // 发送登录成功消息
                        server.log("客户端 " + clientId + " 登录验证成功: " + account);
                    } else {
                        sendMessage("/login_result|failure"); // 发送登录失败消息
                        server.log("客户端 " + clientId + " 登录验证失败: " + account + " (密码错误)");
                        break; // 密码错误，断开连接
                    }
                }
                // 检查是否是公共频道登录消息
                else if (message.startsWith("/login_public|")) {
                    if (!requireVersionForLogin()) {
                        continue;
                    }
                    if (authorizedGroup != null || publicChannelAuthorized) {
                        sendMessage("/login_result|failure: 当前连接已完成频道验证");
                        continue;
                    }
                    String username = message.substring(14); // 提取用户名
                    
                    // 验证用户名是否有效
                    if (username == null || username.trim().isEmpty()) {
                        sendMessage("/login_result|failure: 用户名不能为空");
                        server.log("客户端 " + clientId + " 发送了空的公共频道用户名");
                        break;
                    }

                    if (server.userManager.isReservedServerUsername(username)) {
                        sendMessage("/login_result|failure: server 为服务器保留ID");
                        server.log("客户端 " + clientId + " 尝试使用服务器保留ID: " + username);
                        break;
                    }
                    
                    // 检查用户名是否过长
                    if (server.messageGuard.isUserIdTooLong(username)) {
                        sendMessage("/login_result|failure: 用户名超过" + ChatServer.MAX_USER_ID_BYTES + "字节限制");
                        server.log("客户端 " + clientId + " 的公共频道用户名过长: " + username);
                        break;
                    }

                    if (server.bannedUsers.contains(username)) {
                        sendMessage("您已被服务器禁止，无法加入聊天");
                        server.log("被禁止的用户试图加入公共频道: " + username);
                        break;
                    }
                    if (server.userManager.isUserOnline(username)) {
                        sendMessage("同名用户已在线，无法使用该昵称");
                        server.log("拒绝公共频道重复昵称: " + username);
                        break;
                    }
                    
                    // Only completed chat login counts toward IP connection-rate protection.
                    if (!registerConnectionAdmissionAttempt()) break;

                    // 公共频道登录成功
                    sendMessage("/login_result|success");
                    server.log("客户端 " + clientId + " 公共频道登录成功: " + username);
                    
                    // 自动设置昵称和群组
                    this.nickname = username;
                    server.clientNicknames.put(clientId, nickname);
                    server.webPan.bindUser(webSession, nickname);
                    this.group = ChatServer.PUBLIC_CHANNEL_GROUP;
                    this.publicChannelAuthorized = true;
                    server.clientGroups.put(clientId, group);
                    
                    // 将客户端添加到公共频道群组
                    server.groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);
                    
                    // 添加到在线用户列表
                    server.userManager.addOnlineUser(nickname);
                    server.userManager.notifyMutedStatus(this);
                    registerChatUser();
                    
                    server.log("客户端 " + clientId + " 加入公共频道: " + group);
                    
                    // 发送历史聊天记录给新加入的客户端
                    sendChatHistory();
                }
                // 检查是否是设置群组的特殊消息
                else if (message.startsWith("/group|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝设置群组");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    String newGroup = message.substring(7); // 提取群组部分
                    if (newGroup.isEmpty()) {
                        sendMessage("/group_result|failure: 频道不能为空");
                        continue;
                    }
                    if (!isAuthorizedForGroup(newGroup)) {
                        sendMessage("/group_result|failure: 频道未授权，请先验证频道密码");
                        server.log("客户端 " + clientId + " 尝试未经授权加入频道: " + newGroup);
                        break;
                    }
                    if (group != null && !group.equals(newGroup)) {
                        sendMessage("/group_result|failure: 当前连接已加入频道");
                        server.log("客户端 " + clientId + " 尝试在同一连接切换频道");
                        break;
                    }
                    if (group == null) {
                        this.group = newGroup;
                        server.clientGroups.put(clientId, group);

                        // 将客户端添加到对应群组
                        server.groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);

                        server.log("客户端 " + clientId + " 加入群组: " + group);
                        server.voiceManager.refreshVoiceChannelPanel();

                        // 发送历史聊天记录给新加入的客户端
                        sendChatHistory();
                    }
                }
                // 检查是否是设置昵称的特殊消息
                else if (message.startsWith("/nickname|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝设置昵称");
                        sendMessage("/version_check|failed");
                        break;
                    }
                    if (group == null || !isAuthorizedForGroup(group)) {
                        sendMessage("/session_ready|failure: 请先完成频道登录");
                        server.log("客户端 " + clientId + " 未获频道授权即尝试设置昵称");
                        break;
                    }
                    if (isAuthenticatedChatSession()) {
                        sendMessage("/session_ready|failure: 当前连接已完成登录");
                        server.log("客户端 " + clientId + " 尝试在已登录会话中修改昵称");
                        continue;
                    }

                    String newNickname = message.substring(10); // 提取昵称部分
                    if (!newNickname.isEmpty()) {
                        // 检查昵称是否为保留的"server"名称
                        if (server.userManager.isReservedServerUsername(newNickname)) {
                            sendMessage("昵称 \"server\" 为服务器保留名称，无法使用");
                            server.log("客户端 " + clientId + " 尝试使用服务器保留名称: " + newNickname + "，连接被拒绝");
                            closeConnection();
                            break;
                        }

                        // 检查昵称是否过长
                        if (server.messageGuard.isUserIdTooLong(newNickname)) {
                            sendMessage("服务器拒绝：昵称超过" + ChatServer.MAX_USER_ID_BYTES + "字节限制");
                            server.log("客户端 " + clientId + " 的昵称被拒绝（过长）: " + newNickname);
                            continue;
                        }

                        // 检查用户是否被禁止
                        if (server.bannedUsers.contains(newNickname)) {
                            sendMessage("您已被服务器禁止，无法加入聊天");
                            server.log("被禁止的用户试图加入: " + newNickname);
                            closeConnection();
                            break;
                        }

                        // 检查是否已有同名用户在线
                        if (server.userManager.isUserOnline(newNickname)) {
                            sendMessage("同名用户已在线，无法使用该昵称");
                            server.log("拒绝重复昵称: " + newNickname);
                            closeConnection();
                            break;
                        }

                        if (!registerConnectionAdmissionAttempt()) break;

                        // 更新昵称
                        String oldNickname = nickname;
                        nickname = newNickname;
                        server.clientNicknames.put(clientId, nickname); // 更新昵称映射
                        server.webPan.bindUser(webSession, nickname);

                        // 更新在线用户列表
                        server.userManager.removeOnlineUser(oldNickname);
                        server.userManager.addOnlineUser(nickname);
                        server.userManager.notifyMutedStatus(this);

                        server.log("客户端 " + clientId + " 设置昵称为: " + nickname);
                        
                        registerChatUser();
                    }
                }
                else if (message.equals("/ping")) {
                    // 心跳消息只用于保活，不广播到频道
                    continue;
                }
                // 加入当前文字频道对应的实时语音区
                else if (message.equals("/live_group_join")) {
                    if (!versionChecked || group == null || !server.userHandlers.containsKey(nickname)) {
                        sendMessage("/live_voice_error|请先完成登录并加入频道");
                        continue;
                    }
                    if (!server.voiceChannelEnabled.getOrDefault(group, true)) {
                        sendMessage("/live_voice_error|该语音频道当前已关闭");
                        continue;
                    }
                    if (server.activeP2PVoicePeers.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsValue(nickname)) {
                        sendMessage("/live_voice_error|请先结束或处理私聊语音申请");
                        continue;
                    }
                    Set<ClientHandler> members = server.voiceRooms.computeIfAbsent(group, key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                    members.add(this);
                    sendMessage("/live_group_joined|" + group + "|" + members.size());
                    server.voiceManager.broadcastVoiceRoomMemberCount(group);
                    server.log("用户 " + nickname + " 加入频道语音: " + group);
                }
                // 退出当前频道的实时语音区
                else if (message.equals("/live_group_leave")) {
                    server.voiceManager.leaveVoiceRoom(this, true);
                }
                // 转发当前频道的实时语音块
                else if (message.startsWith("/live_group_audio|")) {
                    if (!server.voiceManager.isVoiceRoomMember(this)) {
                        sendMessage("/live_voice_error|您尚未加入频道语音");
                        continue;
                    }
                    String audioData = message.substring(18);
                    if (audioData.isEmpty() || audioData.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
                        continue;
                    }
                    try {
                        byte[] audioFrame = Base64.getDecoder().decode(audioData);
                        if (audioFrame.length == 0 || audioFrame.length > ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                            continue;
                        }
                        if (audioFrame.length != ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                            audioFrame = Arrays.copyOf(audioFrame, ChatServer.LIVE_AUDIO_CHUNK_BYTES);
                        }
                        Map<ClientHandler, BlockingQueue<byte[]>> senderQueues = server.voiceRoomAudioQueues
                                .computeIfAbsent(group, key -> new java.util.concurrent.ConcurrentHashMap<>());
                        BlockingQueue<byte[]> senderQueue = senderQueues.computeIfAbsent(this,
                                key -> new ArrayBlockingQueue<>(ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY));
                        server.voiceManager.offerAudioFrame(senderQueue, audioFrame, ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY);
                    } catch (IllegalArgumentException ignored) {
                        // 忽略无效Base64音频帧
                    }
                }
                // 向私聊对象申请实时语音
                else if (message.startsWith("/live_p2p_request|")) {
                    if (!versionChecked || !server.userHandlers.containsKey(nickname)) {
                        sendMessage("/live_voice_error|请先完成登录");
                        continue;
                    }
                    if (server.voiceManager.isVoiceRoomMember(this) || server.activeP2PVoicePeers.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsValue(nickname)) {
                        sendMessage("/live_voice_error|您当前已有语音会话或待处理申请");
                        continue;
                    }
                    String targetPassword = message.substring(18);
                    String targetUser = server.passwordToUser.get(targetPassword);
                    ClientHandler targetHandler = targetUser == null ? null : server.userHandlers.get(targetUser);
                    if (targetHandler == null || targetUser.equals(nickname)) {
                        sendMessage("/live_voice_error|用户不存在或已离线");
                        continue;
                    }
                    if (server.voiceManager.isVoiceRoomMember(targetHandler) || server.activeP2PVoicePeers.containsKey(targetUser)
                            || server.pendingP2PVoiceRequests.containsKey(targetUser)
                            || server.pendingP2PVoiceRequests.containsValue(targetUser)) {
                        sendMessage("/live_voice_error|对方当前正在通话或有待处理申请");
                        continue;
                    }
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/live_voice_error|系统未分配私聊密码");
                        continue;
                    }
                    server.pendingP2PVoiceRequests.put(targetUser, nickname);
                    server.pendingP2PVoiceRequestTimes.put(targetUser, System.currentTimeMillis());
                    targetHandler.sendMessage("/live_p2p_request|" + nickname + "|" + senderPassword);
                    sendMessage("/live_p2p_request_sent|" + targetUser);
                    server.log("私聊语音申请: " + nickname + " -> " + targetUser);
                }
                // 同意私聊语音申请
                else if (message.startsWith("/live_p2p_accept|")) {
                    String requesterPassword = message.substring(17);
                    if (server.voiceManager.handleServerP2PVoiceAccept(this, requesterPassword)) {
                        continue;
                    }
                    String requester = server.passwordToUser.get(requesterPassword);
                    if (requester == null || !requester.equals(server.pendingP2PVoiceRequests.get(nickname))) {
                        sendMessage("/live_voice_error|语音申请已失效");
                        continue;
                    }
                    ClientHandler requesterHandler = server.userHandlers.get(requester);
                    if (requesterHandler == null || server.voiceManager.isVoiceRoomMember(this) || server.voiceManager.isVoiceRoomMember(requesterHandler)
                            || server.activeP2PVoicePeers.containsKey(nickname) || server.activeP2PVoicePeers.containsKey(requester)) {
                        server.pendingP2PVoiceRequests.remove(nickname, requester);
                        server.pendingP2PVoiceRequestTimes.remove(nickname);
                        sendMessage("/live_voice_error|双方当前无法建立语音通话");
                        if (requesterHandler != null) {
                            requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方当前无法接听");
                        }
                        continue;
                    }
                    server.pendingP2PVoiceRequests.remove(nickname, requester);
                    server.pendingP2PVoiceRequestTimes.remove(nickname);
                    server.activeP2PVoicePeers.put(nickname, requester);
                    server.activeP2PVoicePeers.put(requester, nickname);
                    String myPassword = server.userP2PPasswords.get(nickname);
                    requesterHandler.sendMessage("/live_p2p_started|" + nickname + "|" + myPassword);
                    sendMessage("/live_p2p_started|" + requester + "|" + requesterPassword);
                    server.log("私聊语音已建立: " + nickname + " <-> " + requester);
                }
                // 拒绝私聊语音申请
                else if (message.startsWith("/live_p2p_reject|")) {
                    String requesterPassword = message.substring(17);
                    if (server.voiceManager.handleServerP2PVoiceReject(this, requesterPassword)) {
                        continue;
                    }
                    String requester = server.passwordToUser.get(requesterPassword);
                    if (requester != null && server.pendingP2PVoiceRequests.remove(nickname, requester)) {
                        server.pendingP2PVoiceRequestTimes.remove(nickname);
                        ClientHandler requesterHandler = server.userHandlers.get(requester);
                        if (requesterHandler != null) {
                            requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方已拒绝");
                        }
                        sendMessage("/live_p2p_rejected_ack|" + requester);
                    }
                }
                // 转发已建立私聊通话的实时语音块
                else if (message.startsWith("/live_p2p_audio|")) {
                    if (server.voiceManager.handleServerP2PVoiceAudio(nickname, message.substring(16))) {
                        continue;
                    }
                    String peer = server.activeP2PVoicePeers.get(nickname);
                    if (peer == null) {
                        continue;
                    }
                    String audioData = message.substring(16);
                    if (audioData.isEmpty() || audioData.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
                        continue;
                    }
                    ClientHandler peerHandler = server.userHandlers.get(peer);
                    if (peerHandler != null) {
                        peerHandler.sendMessage("/live_p2p_audio|" + nickname + "|" + audioData);
                    } else {
                        server.voiceManager.endP2PVoiceCall(nickname);
                    }
                }
                // 挂断私聊语音
                else if (message.equals("/live_p2p_end")) {
                    if (nickname.equals(server.serverP2PVoicePeer)) {
                        server.voiceManager.stopServerP2PVoiceSession(false);
                        continue;
                    }
                    server.voiceManager.endP2PVoiceCall(nickname);
                }
                // 检查是否是语音消息
                else if (message.startsWith("/voice|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送语音消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        // 验证消息格式
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            server.log("客户端 " + clientId + " 发送的语音消息格式错误");
                            continue;
                        }
                        String voiceId = parts[1];
                        server.log("收到来自 " + nickname + " 的语音消息，ID: " + voiceId);
                        // 保存语音消息到聊天记录
                        ChatMessage chatMsg = new ChatMessage(nickname, "[语音消息]");
                        server.history.saveChatHistory(group, chatMsg);
                        // 创建包含发送者信息的语音消息
                        String voiceWithSender = "/voice_with_sender|" + nickname + "|" + voiceId + "|" + parts[2];
                        broadcastSpecialMessage(voiceWithSender, this);  // 在群组内广播带发送者信息的语音消息
                    }
                }
                // 检查是否是图片信息消息
                else if (message.startsWith("/image_info|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片信息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        String[] parts = message.split("\\|", 4);
                        if (parts.length == 4) {
                            String imageId = parts[1];
                            int totalChunks;
                            try {
                                totalChunks = Integer.parseInt(parts[2]);
                            } catch (NumberFormatException e) {
                                server.log("客户端 " + clientId + " 发送了无效的图片块数量");
                                continue;
                            }

                            String fileName = parts[3];
                            server.log("收到来自 " + nickname + " 的图片信息: " + fileName + " (" + totalChunks + " 块)");

                            // 创建图片接收器
                            ImageChunkReceiver receiver = new ImageChunkReceiver(imageId, fileName, group, nickname, totalChunks);
                            server.imageReceivers.put(imageId, receiver);

                            // 广播图片信息到群组
                            broadcastSpecialMessage(message, this);
                        }
                    }
                }
                // 检查是否是图片块消息
                else if (message.startsWith("/image_chunk|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片块");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        String[] parts = message.split("\\|", 4);
                        if (parts.length == 4) {
                            String imageId = parts[1];
                            int chunkIndex;
                            try {
                                chunkIndex = Integer.parseInt(parts[2]);
                            } catch (NumberFormatException e) {
                                server.log("客户端 " + clientId + " 发送了无效的图片块索引");
                                continue;
                            }
                            String chunkData = parts[3];

                            // 查找对应的图片接收器
                            ImageChunkReceiver receiver = server.imageReceivers.get(imageId);
                            if (receiver != null) {
                                boolean isComplete = receiver.addChunk(chunkIndex, chunkData);
                                if (isComplete) {
                                    // 图片接收完成，保存到文件
                                    server.saveImageToFile(receiver);
                                    server.imageReceivers.remove(imageId);
                                }
                            }
                        }

                        // 直接转发图片块消息到群组
                        broadcastSpecialMessage(message, this);
                    }
                }
                // 检查是否是点对点验证请求
                else if (message.startsWith("/p2p_verify|")) {
                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送验证请求");
                        continue;
                    }

                    // 解析点对点验证格式: /p2p_verify|targetPassword
                    String[] parts = message.split("\\|", 2);
                    if (parts.length != 2) {
                        server.log("客户端 " + clientId + " 发送的点对点验证格式错误");
                        continue;
                    }
                    String targetPassword = parts[1];
                    if (!allowP2pVerificationAttempt()) {
                        continue;
                    }
                    
                    // 查找目标用户
                    String targetUser = server.passwordToUser.get(targetPassword);
                    if (targetUser == null) {
                        recordP2pVerificationFailure();
                        sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试验证不存在的密码: " + targetPassword);
                        continue;
                    }
                    
                    // 查找目标用户的处理器
                    ClientHandler targetHandler = server.userHandlers.get(targetUser);
                    if (targetHandler == null) {
                        recordP2pVerificationFailure();
                        sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试验证离线用户: " + targetUser);
                        continue;
                    }
                    
                    // 获取发送者的密码
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/p2p_verify_result|error|系统错误，发送者密码未生成");
                        server.log("发送者密码未找到: " + nickname);
                        continue;
                    }
                    
                    // 向目标用户发送通知，格式: /p2p_notification|sender|senderPassword
                    targetHandler.sendMessage("/p2p_notification|" + nickname + "|" + senderPassword);
                    
                    // 向发起用户发送验证成功
                    p2pVerificationFailures = 0;
                    nextP2pVerificationAt = 0L;
                    sendMessage("/p2p_verify_result|success");
                    server.log("点对点验证成功，从 " + nickname + " 发送通知给 " + targetUser);
                }
                // 检查是否是点对点消息
                else if (message.startsWith("/p2p|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送点对点消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    // 解析点对点消息格式: /p2p|targetPassword|message
                    String[] parts = message.split("\\|", 3);
                    if (parts.length != 3) {
                        server.log("客户端 " + clientId + " 发送的点对点消息格式错误");
                        continue;
                    }
                    String targetPassword = parts[1];
                    String content = parts[2];
                    
                    // 查找目标用户
                    String targetUser = server.passwordToUser.get(targetPassword);
                    if (targetUser == null) {
                        sendMessage("/p2p_error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试向不存在的密码发送点对点消息: " + targetPassword);
                        continue;
                    }
                    
                    // 查找目标用户的处理器
                    ClientHandler targetHandler = server.userHandlers.get(targetUser);
                    if (targetHandler == null) {
                        sendMessage("/p2p_error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试向离线用户发送点对点消息: " + targetUser);
                        continue;
                    }
                    
                    // 获取发送者的密码
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/p2p_error|系统错误，发送者密码未生成");
                        server.log("发送者密码未找到: " + nickname);
                        continue;
                    }
                    
                    // 转发消息给目标用户，格式: /p2p_msg|sender|senderPassword|content
                    targetHandler.sendMessage("/p2p_msg|" + nickname + "|" + senderPassword + "|" + content);
                    server.log("点对点消息从 " + nickname + " 转发给 " + targetUser);
                }
                else if (message.startsWith("/deepseek|")) {
                    // DeepSeek AI问答
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送DeepSeek请求");
                        sendMessage("/version_check|failed");
                        break;
                    }
                    
                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法使用DeepSeek");
                        continue;
                    }
                    
                    // 提取问题内容
                    String question = message.substring(10); // 移除 "/deepseek|"
                    if (question.isEmpty()) {
                        sendMessage("DeepSeek: 问题不能为空");
                        continue;
                    }
                    
                    // 调用DeepSeek服务
                    String answer = server.deepSeekService.ask(nickname, question);
                    // 发送答案给请求用户
                    sendMessage("/deepseek_answer|" + answer);
                    server.log("DeepSeek回答已发送给 " + nickname);
                }
                else if (message.startsWith("/game_record|")) {
                    // 小游戏战绩记录（网页端小游戏）
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝保存游戏记录");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法保存游戏记录");
                        continue;
                    }

                    // 解析游戏记录格式: /game_record|记录文件|游戏名|得分
                    String[] parts = message.split("\\|", 4);
                    if (parts.length != 4) {
                        server.log("客户端 " + clientId + " 发送的游戏记录格式错误");
                        continue;
                    }
                    String recordFile = parts[1];
                    if (!server.gameRecordStore.isAllowedFile(recordFile)) {
                        sendMessage("/web_error|无效的游戏记录文件");
                        continue;
                    }
                    String gamesName = parts[2].trim();
                    int score;
                    try {
                        score = Integer.parseInt(parts[3].trim());
                    } catch (NumberFormatException e) {
                        sendMessage("/web_error|游戏得分无效");
                        continue;
                    }
                    if (gamesName.isEmpty() || gamesName.length() > 30) {
                        sendMessage("/web_error|游戏名称无效");
                        continue;
                    }
                    if (score < 0 || score > 1000000) {
                        sendMessage("/web_error|游戏得分超出范围");
                        continue;
                    }

                    server.gameRecordStore.saveRecord(recordFile, nickname, gamesName, score);
                    sendMessage("/game_record_saved|ok");
                    server.log("游戏记录: " + nickname + " 在 " + gamesName + " 中得分 " + score);
                }
                else if (message.startsWith("/game_rank|")) {
                    // 小游戏排行榜（前百，同名用户取历史最高分）
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送排行榜");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 解析排行榜请求格式: /game_rank|记录文件|游戏名
                    String[] parts = message.split("\\|", 3);
                    if (parts.length != 3) {
                        server.log("客户端 " + clientId + " 发送的排行榜请求格式错误");
                        continue;
                    }
                    String recordFile = parts[1];
                    if (!server.gameRecordStore.isAllowedFile(recordFile)) {
                        sendMessage("/web_error|无效的游戏记录文件");
                        continue;
                    }
                    String gamesName = parts[2].trim();
                    if (gamesName.isEmpty() || gamesName.length() > 30) {
                        sendMessage("/web_error|游戏名称无效");
                        continue;
                    }

                    List<GameRecordStore.RankEntry> ranks = server.gameRecordStore.getTopRank(recordFile, gamesName, 100);
                    StringBuilder payload = new StringBuilder();
                    for (int i = 0; i < ranks.size(); i++) {
                        GameRecordStore.RankEntry entry = ranks.get(i);
                        if (i > 0) {
                            payload.append('\n');
                        }
                        payload.append(i + 1).append('\t').append(entry.id).append('\t').append(entry.score);
                    }
                    sendMessage("/game_rank_result|" + server.http.encodeWebValue(gamesName) + "|"
                            + server.http.encodeWebValue(payload.toString()));
                    server.log("排行榜已发送: " + gamesName + " 共 " + ranks.size() + " 条");
                }
                else if (message.startsWith("/mp_")) {
                    // 3D 射击生存 · 多人模式（需要先登录）
                    if (group == null || !server.userHandlers.containsKey(nickname)) {
                        sendMessage("/mp_error|请先登录聊天");
                        continue;
                    }
                    if (message.equals("/mp_list")) {
                        StringBuilder sb = new StringBuilder();
                        List<FpsLobbyManager.FpsServerInfo> list = server.fpsLobby.list();
                        for (int i = 0; i < list.size(); i++) {
                            FpsLobbyManager.FpsServerInfo info = list.get(i);
                            if (i > 0) {
                                sb.append('\n');
                            }
                            sb.append(info.serverId).append('|').append(info.hostName).append('|')
                                    .append(info.maxPlayers).append('|').append(info.players.size()).append('|')
                                    .append(info.password.isEmpty() ? "0" : "1").append('|')
                                    .append(info.botsEnabled ? "1" : "0");
                        }
                        sendMessage("/mp_list_result|" + server.http.encodeWebValue(sb.toString()));
                    }
                    else if (message.startsWith("/mp_create|")) {
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            sendMessage("/mp_create_result|error|参数错误");
                            continue;
                        }
                        int maxPlayers;
                        try {
                            maxPlayers = Integer.parseInt(parts[1].trim());
                        } catch (NumberFormatException e) {
                            sendMessage("/mp_create_result|error|人数无效");
                            continue;
                        }
                        String error = server.fpsLobby.create(nickname, maxPlayers, parts[2]);
                        if (error != null) {
                            sendMessage("/mp_create_result|error|" + error);
                            continue;
                        }
                        String id = server.fpsLobby.serverIdOf(nickname);
                        FpsLobbyManager.FpsServerInfo createdInfo = server.fpsLobby.get(id);
                        long serverNow = System.currentTimeMillis();
                        sendMessage("/mp_create_result|ok|" + id + "|"
                                + (createdInfo == null ? serverNow : createdInfo.musicStartAt) + "|" + serverNow);
                        // 房间地图和共享道具由服务器统一下发，房主也必须使用这份房间状态。
                        server.fpsLobby.sendRoomSnapshot(id, nickname);
                    }
                    else if (message.startsWith("/mp_join|")) {
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            sendMessage("/mp_join_result|error|参数错误");
                            continue;
                        }
                        String error = server.fpsLobby.join(nickname, parts[1], parts[2]);
                        if (error != null) {
                            sendMessage("/mp_join_result|error|" + error);
                            continue;
                        }
                        // 加入结果携带机器人开关状态与房主名（加入者据此刻画同一游戏环境）
                        FpsLobbyManager.FpsServerInfo joinedInfo = server.fpsLobby.get(parts[1]);
                        String joinExtra = joinedInfo != null
                                ? "|" + (joinedInfo.botsEnabled ? "1" : "0") + "|" + joinedInfo.hostName
                                + "|" + joinedInfo.musicStartAt + "|" + System.currentTimeMillis() : "";
                        sendMessage("/mp_join_result|ok|" + parts[1] + joinExtra);
                        // 先补发同一房间的地图、共享道具及已在场玩家快照。
                        server.fpsLobby.sendRoomSnapshot(parts[1], nickname);
                        // 明确要求已在场玩家补发一次状态，保证触屏/桌面端加入时都能看到对方模型。
                        server.fpsLobby.broadcast(parts[1],
                                "/mp_sync_request|" + server.http.encodeWebValue(nickname), nickname);
                        // 新玩家也收到加入通知，触发其状态广播，确保双方状态都能快速建立。
                        server.fpsLobby.broadcast(parts[1],
                                "/mp_peer_joined|" + server.http.encodeWebValue(nickname), null);
                    }
                    else if (message.equals("/mp_leave")) {
                        server.fpsLobby.leave(nickname);
                        sendMessage("/mp_leave_ok");
                    }
                    else if (message.equals("/mp_world_request")) {
                        // 客户端完成多人场景初始化后主动拉取一次房间权威状态。
                        String sid = server.fpsLobby.serverIdOf(nickname);
                        if (sid != null) {
                            server.fpsLobby.sendRoomSnapshot(sid, nickname);
                        }
                    }
                    else if (message.startsWith("/mp_bots|")) {
                        String[] parts = message.split("\\|", 2);
                        if (parts.length != 2) {
                            continue;
                        }
                        String sid = server.fpsLobby.serverIdOf(nickname);
                        if (sid == null || !server.fpsLobby.isHost(nickname, sid)) {
                            sendMessage("/mp_error|只有房主可以开关机器人");
                            continue;
                        }
                        boolean on = "on".equals(parts[1]);
                        server.fpsLobby.setBots(nickname, on);
                        server.fpsLobby.broadcast(sid, "/mp_bots|" + (on ? "on" : "off"), null);
                    }
                    else if (message.startsWith("/mp_boss_spawn|")) {
                        // /mp_boss_spawn|x|z：只有房主可以在服务器计时到点后生成 Boss。
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3 || !isFiniteDouble(parts[1]) || !isFiniteDouble(parts[2])) {
                            continue;
                        }
                        double x = Double.parseDouble(parts[1]);
                        double z = Double.parseDouble(parts[2]);
                        server.fpsLobby.requestBossSpawn(nickname, x, z);
                    }
                    else if (message.startsWith("/mp_boss_move|")) {
                        // /mp_boss_move|代数|x|z：房主同步 Boss 的移动位置。
                        String[] parts = message.split("\\|", 4);
                        if (parts.length != 4 || !isInteger(parts[1])
                                || !isFiniteDouble(parts[2]) || !isFiniteDouble(parts[3])) {
                            continue;
                        }
                        server.fpsLobby.updateBossPosition(nickname, Integer.parseInt(parts[1]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                    }
                    else if (message.startsWith("/mp_boss_wave|")) {
                        // /mp_boss_wave|代数|x|z：服务器节流后向全房间广播同一波次。
                        String[] parts = message.split("\\|", 4);
                        if (parts.length != 4 || !isInteger(parts[1])
                                || !isFiniteDouble(parts[2]) || !isFiniteDouble(parts[3])) {
                            continue;
                        }
                        server.fpsLobby.requestBossWave(nickname, Integer.parseInt(parts[1]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                    }
                    else if (message.startsWith("/mp_boss_hit|")) {
                        // /mp_boss_hit|代数|伤害|武器索引|攻击者生命编号
                        String[] parts = message.split("\\|", 5);
                        if (parts.length != 5 || !isInteger(parts[1]) || !isInteger(parts[2])
                                || !isInteger(parts[3]) || !isInteger(parts[4])) {
                            continue;
                        }
                        FpsLobbyManager.BossHitResult result = server.fpsLobby.recordBossHit(nickname,
                                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                        if (result != null && result.killed) {
                            server.fpsLobby.sendTo(result.attacker,
                                    "/mp_boss_reward|" + result.attackerLifeGeneration + "|" + result.weaponIndex);
                        }
                    }
                    else if (message.startsWith("/mp_boss_attack|")) {
                        // /mp_boss_attack|目标昵称|Boss 代数
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3 || !isInteger(parts[2])) {
                            continue;
                        }
                        String target;
                        try {
                            target = server.http.decodeWebValue(parts[1]);
                        } catch (IOException ignored) {
                            continue;
                        }
                        server.fpsLobby.forwardBossAttack(nickname, target, Integer.parseInt(parts[2]), 75);
                    }
                    else if (message.startsWith("/mp_bot_state|")) {
                        // 普通机器人状态只能由房主上报；服务器鉴权后转发给成员。
                        String[] parts = message.split("\\|", 7);
                        if (parts.length != 7 || !isInteger(parts[2]) || !isFiniteDouble(parts[3])
                                || !isFiniteDouble(parts[4]) || !isFiniteDouble(parts[5])
                                || !("0".equals(parts[6]) || "1".equals(parts[6]))) {
                            continue;
                        }
                        String botId;
                        try {
                            botId = server.http.decodeWebValue(parts[1]);
                        } catch (IOException ignored) {
                            continue;
                        }
                        server.fpsLobby.forwardBotState(nickname, botId, Integer.parseInt(parts[2]),
                                Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5]), "1".equals(parts[6]));
                    }
                    else if (message.startsWith("/mp_bot_hit|")) {
                        // 成员只提交命中请求；服务器验证生命状态和伤害后转发给权威房主。
                        String[] parts = message.split("\\|", 5);
                        if (parts.length != 5 || !isInteger(parts[2]) || !isInteger(parts[3])
                                || !isInteger(parts[4])) {
                            continue;
                        }
                        String botId;
                        try {
                            botId = server.http.decodeWebValue(parts[1]);
                        } catch (IOException ignored) {
                            continue;
                        }
                        server.fpsLobby.forwardBotHit(nickname, botId, Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                    }
                    else if (message.startsWith("/mp_bot_kill|")) {
                        // 只有房主能确认普通机器人死亡和击杀者。
                        String[] parts = message.split("\\|", 5);
                        if (parts.length != 5 || !isInteger(parts[3]) || !isInteger(parts[4])) {
                            continue;
                        }
                        String botId;
                        String attacker;
                        try {
                            botId = server.http.decodeWebValue(parts[1]);
                            attacker = server.http.decodeWebValue(parts[2]);
                        } catch (IOException ignored) {
                            continue;
                        }
                        server.fpsLobby.confirmBotKill(nickname, botId, attacker,
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                    }
                    else if (message.startsWith("/mp_bot_attack|")) {
                        // 普通机器人攻击同样只能由房主判定并经服务器发送伤害。
                        String[] parts = message.split("\\|", 4);
                        if (parts.length != 4 || !isInteger(parts[2])) {
                            continue;
                        }
                        String target;
                        String botId;
                        try {
                            target = server.http.decodeWebValue(parts[1]);
                            botId = server.http.decodeWebValue(parts[3]);
                        } catch (IOException ignored) {
                            continue;
                        }
                        server.fpsLobby.forwardBotAttack(nickname, target, Integer.parseInt(parts[2]), botId);
                    }
                    else if (message.startsWith("/mp_state|")) {
                        // 转发玩家状态给同服务器其他成员
                        String data = message.substring(10);
                        String sid = server.fpsLobby.serverIdOf(nickname);
                        if (sid != null && data.length() < 200) {
                            server.fpsLobby.updatePlayerState(nickname, data);
                        }
                    }
                    else if (message.startsWith("/mp_pickup|")) {
                        // 道具是多人游戏房间公用的：一次拾取会同步隐藏和刷新时间。
                        String[] parts = message.split("\\|", 2);
                        if (parts.length != 2) {
                            continue;
                        }
                        String pickupState = server.fpsLobby.claimPickup(nickname, parts[1]);
                        if (pickupState != null) {
                            String[] stateParts = pickupState.split("\\|", 4);
                            String sid = stateParts[0];
                            server.fpsLobby.broadcast(sid,
                                    "/mp_pickup_state|" + stateParts[1] + "|" + stateParts[2] + "|" + stateParts[3]
                                            + "|" + server.http.encodeWebValue(nickname), null);
                        }
                    }
                    else if (message.startsWith("/mp_death|")) {
                        // /mp_death|致死命中的服务端 hitId|受害者生命编号
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            continue;
                        }
                        int lifeGeneration;
                        try {
                            lifeGeneration = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        FpsLobbyManager.KillReward reward = server.fpsLobby.recordPlayerDeath(
                                nickname, parts[1], lifeGeneration);
                        if (reward != null) {
                            server.fpsLobby.sendTo(reward.attacker,
                                    "/mp_kill_reward|" + reward.attackerLifeGeneration + "|"
                                            + reward.weaponIndex + "|" + FpsLobbyManager.playerKillReserveReward(reward.weaponIndex) + "|"
                                            + FpsLobbyManager.playerKillHealthReward(reward.weaponIndex) + "|"
                                            + server.http.encodeWebValue(reward.victim));
                        }
                    }
                    else if (message.startsWith("/mp_hit|")) {
                        // /mp_hit|目标昵称|伤害|武器索引|攻击者生命编号|目标生命编号
                        String[] parts = message.split("\\|", 6);
                        if (parts.length != 6) {
                            continue;
                        }
                        int damage;
                        int weaponIndex;
                        int attackerLifeGeneration;
                        int targetLifeGeneration;
                        try {
                            damage = Integer.parseInt(parts[2]);
                            weaponIndex = Integer.parseInt(parts[3]);
                            attackerLifeGeneration = Integer.parseInt(parts[4]);
                            targetLifeGeneration = Integer.parseInt(parts[5]);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (!FpsLobbyManager.isValidBulletDamage(weaponIndex, damage)) {
                            continue;
                        }
                        FpsLobbyManager.PendingHit hit = server.fpsLobby.recordPlayerAttack(nickname, parts[1],
                                weaponIndex, attackerLifeGeneration, targetLifeGeneration);
                        if (hit == null) {
                            continue;
                        }
                        server.fpsLobby.sendTo(parts[1],
                                "/mp_hit|" + server.http.encodeWebValue(nickname) + "|" + damage + "|" + hit.hitId);
                    }
                    else if (message.startsWith("/mp_melee|")) {
                        // /mp_melee|目标昵称|武器索引|攻击者生命编号|目标生命编号
                        String[] parts = message.split("\\|", 5);
                        if (parts.length != 5) {
                            continue;
                        }
                        int weaponIndex;
                        int attackerLifeGeneration;
                        int targetLifeGeneration;
                        try {
                            weaponIndex = Integer.parseInt(parts[2]);
                            attackerLifeGeneration = Integer.parseInt(parts[3]);
                            targetLifeGeneration = Integer.parseInt(parts[4]);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        FpsLobbyManager.PendingHit hit = server.fpsLobby.recordPlayerAttack(nickname, parts[1],
                                weaponIndex, attackerLifeGeneration, targetLifeGeneration);
                        if (hit == null) {
                            continue;
                        }
                        server.fpsLobby.sendTo(parts[1],
                                "/mp_melee|" + server.http.encodeWebValue(nickname) + "|" + hit.hitId);
                    }
                }
                else {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        // 检查消息是否过长
                        if (server.messageGuard.isMessageTooLong(message)) {
                            server.log("客户端 " + clientId + " 发送的消息被拒绝（内容过长）");
                            // 发送警告给客户端
                            sendMessage("服务器拒绝：消息超过" + ChatServer.MAX_MESSAGE_BYTES + "字节限制");
                            continue; // 拒绝过长的消息
                        }

                        // 检查消息是否包含连续5个相同字符
                        if (server.messageGuard.hasTooManyConsecutiveSameChars(message)) {
                            server.log("客户端 " + clientId + " 发送的消息被拒绝（包含连续5个相同字符）: " + message);
                            sendMessage("服务器拒绝：消息包含连续5个相同字符");
                            continue; // 拒绝包含连续5个相同字符的消息
                        }

                        // 检查是否为重复消息（10分钟内）
                        if (server.messageGuard.isDuplicateMessage(group, message)) {
                            server.log("客户端 " + clientId + " 发送的重复消息被拒绝");
                            sendMessage("服务器拒绝：10分钟内不允许发送相同消息");
                            continue; // 拒绝重复的消息
                        }

                        server.log("收到来自 " + nickname + " 的消息: " + message);
                        
                        // 检查是否为公共频道，如果是则进行违禁词检测
                        String messageToBroadcast = message;
                        if (ChatServer.PUBLIC_CHANNEL_GROUP.equals(group)) {
                            // 检查是否包含违禁词
                            if (server.messageGuard.containsForbiddenWords(message)) {
                                server.log("检测到违禁词，消息将被过滤: " + message);
                                messageToBroadcast = server.messageGuard.filterForbiddenWords(message);
                            }
                        }
                        
                        // 保存消息到聊天记录（保存原始消息，但广播过滤后的消息）
                        ChatMessage chatMsg = new ChatMessage(nickname, messageToBroadcast);
                        server.history.saveChatHistory(group, chatMsg);
                        broadcast(messageToBroadcast, this);  // 在群组内广播消息
                    }
                }
            }
        } catch (IOException e) {
            server.log("客户端 " + nickname + " 意外断开连接");
        } finally {
            // 清理客户端资源
            cleanupClient();
            server.log("客户端 " + nickname + " 连接已清理");
        }
    }

    /** Direct TCP clients must prove they can read the one-time connection challenge. */
    private boolean handleTcpVerification(String message) throws IOException {
        // Accept a bare number for simple terminal clients, while retaining an
        // explicit command for clients that use the line protocol.
        String supplied = message.startsWith("/tcp_verify|")
                ? message.substring("/tcp_verify|".length()).trim() : message.trim();
        if (supplied.isEmpty() || !supplied.matches("\\d{1,3}")) {
            sendMessage("/tcp_verify_required|请先回答连接验证码");
            return false;
        }
        try {
            if (Integer.parseInt(supplied) != tcpChallengeAnswer) {
                sendMessage("/tcp_verify_result|failure");
                return false;
            }
        } catch (NumberFormatException e) {
            sendMessage("/tcp_verify_result|failure");
            return false;
        }
        tcpVerified = true;
        sendMessage("/tcp_verify_result|success");
        return true;
    }

    private boolean handleWebControlMessage(String message) throws IOException {
        if (message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            sendMessage("/web_error|消息不能包含换行符");
            return false;
        }
        if (!webVerified) {
            if (!message.startsWith("/web_verify|")) {
                sendMessage("/web_verify_result|required");
                return false;
            }
            String supplied;
            try {
                supplied = server.http.decodeWebValue(message.substring(12)).trim();
            } catch (IOException e) {
                supplied = "";
            }
            webVerificationAttempts++;
            if (server.http.constantTimeEquals(supplied, server.webVerificationAnswer)) {
                webVerified = true;
                lastWebUserActivity = System.currentTimeMillis();
                server.webPan.authorize(webSession, () -> webVerified && handlerActive
                        && System.currentTimeMillis() - lastWebUserActivity < ChatServer.WEB_IDLE_TIMEOUT);
                sendMessage("/web_verify_result|success");
                sendMessage("/web_minimum_version|" + server.minimumClientVersion);
                server.http.sendWebChannelList(this);
                server.log("网页访问验证通过: " + clientId);
            } else {
                int remaining = Math.max(0, ChatServer.WEB_MAX_VERIFY_ATTEMPTS - webVerificationAttempts);
                sendMessage("/web_verify_result|failure|" + remaining);
                if (remaining == 0) {
                    server.log("网页访问验证失败过多，已断开: " + clientId);
                    closeConnection();
                } else {
                    try {
                        Thread.sleep(Math.min(1000L, webVerificationAttempts * 250L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return false;
        }

        if (message.equals("/web_disconnect")) {
            closeConnection();
            return false;
        }
        if (message.equals("/web_channels_request")) {
            server.http.sendWebChannelList(this);
            return false;
        }
        if (!isAllowedWebClientMessage(message)) {
            sendMessage("/web_error|不支持的网页协议命令");
            return false;
        }
        if (message.startsWith("/login|") && !versionChecked) {
            sendMessage("/login_result|failure: 请先完成版本验证");
            return false;
        }
        if (message.startsWith("/group|")) {
            String requestedGroup = message.substring(7);
            boolean publicGroup = ChatServer.PUBLIC_CHANNEL_GROUP.equals(requestedGroup);
            if (!versionChecked || (!publicGroup && !requestedGroup.equals(webAuthorizedGroup))) {
                sendMessage("/web_login_error|频道未授权，请先验证频道密码");
                return false;
            }
            if (group != null && !group.equals(requestedGroup)) {
                sendMessage("/web_login_error|当前连接已加入频道");
                return false;
            }
        }
        if (message.startsWith("/nickname|")) {
            String requestedNickname = message.substring(10).trim();
            if (requestedNickname.indexOf('|') >= 0 || requestedNickname.indexOf(',') >= 0
                    || requestedNickname.isEmpty()) {
                sendMessage("/web_login_error|昵称包含不允许的字符");
                return false;
            }
            if (group == null) {
                sendMessage("/web_login_error|请先加入频道");
                return false;
            }
        }
        if (!message.startsWith("/") && !server.userHandlers.containsKey(nickname)) {
            sendMessage("/web_error|请先完成频道登录");
            return false;
        }
        return true;
    }

    /** Only handshake and login commands may run before a registered chat session exists. */
    private boolean requiresAuthenticatedChatSession(String message) {
        return !(message.startsWith("/version|")
                || message.startsWith("/login|")
                || message.startsWith("/login_public|")
                || message.startsWith("/group|")
                || message.startsWith("/nickname|")
                || message.equals("/ping"));
    }

    private boolean requireAuthenticatedChatSession(String message) {
        if (isAuthenticatedChatSession()) {
            return true;
        }
        server.log("客户端 " + clientId + " 未完成登录即发送协议: "
                + (message.startsWith("/") ? message.split("\\|", 2)[0] : "普通消息"));
        sendMessage("/auth_required|请先完成频道登录");
        return false;
    }

    private boolean isAuthenticatedChatSession() {
        return versionChecked && group != null && nickname != null && server.userHandlers.get(nickname) == this;
    }

    private boolean requireVersionForLogin() {
        if (versionChecked) {
            return true;
        }
        server.log("客户端 " + clientId + " 未通过版本验证，拒绝登录");
        sendMessage("/login_result|failure: 请先完成版本验证");
        return false;
    }

    private boolean isAuthorizedForGroup(String requestedGroup) {
        // Raw clients must use /login_public before joining the built-in public
        // channel. Web clients are authorized by the web challenge and private
        // channels are authorized by their password login.
        return (ChatServer.PUBLIC_CHANNEL_GROUP.equals(requestedGroup)
                    && (publicChannelAuthorized || webClient))
                || (authorizedGroup != null && authorizedGroup.equals(requestedGroup));
    }

    /** Registers the nickname as the unique authenticated identity and creates its P2P credential. */
    private void registerChatUser() {
        String p2pPassword = server.userManager.generateP2PPassword();
        server.userP2PPasswords.put(nickname, p2pPassword);
        server.passwordToUser.put(p2pPassword, nickname);
        server.userHandlers.put(nickname, this);
        sendMessage("/p2p_password|" + p2pPassword);
        server.userManager.broadcastOnlineUsers();
        sendMessage("/session_ready|success");
    }

    private boolean allowP2pVerificationAttempt() {
        long now = System.currentTimeMillis();
        if (now >= nextP2pVerificationAt) {
            return true;
        }
        long waitSeconds = Math.max(1L, (nextP2pVerificationAt - now + 999L) / 1000L);
        sendMessage("/p2p_verify_result|error|验证请求过于频繁，请 " + waitSeconds + " 秒后重试");
        return false;
    }

    private void recordP2pVerificationFailure() throws IOException {
        p2pVerificationFailures++;
        nextP2pVerificationAt = System.currentTimeMillis()
                + Math.min(1_000L, p2pVerificationFailures * 250L);
        if (p2pVerificationFailures >= 5) {
            server.log("客户端 " + nickname + " 点对点验证连续失败过多，已断开");
            sendMessage("/p2p_verify_result|error|验证失败次数过多，请重新登录后再试");
            closeConnection();
        }
    }

    private boolean isAllowedWebClientMessage(String message) {
        if (!message.startsWith("/")) {
            return true;
        }
        return message.startsWith("/version|")
                || message.startsWith("/login|")
                || message.startsWith("/group|")
                || message.startsWith("/nickname|")
                || message.equals("/ping")
                || message.equals("/live_group_join")
                || message.equals("/live_group_leave")
                || message.startsWith("/live_group_audio|")
                || message.startsWith("/live_p2p_request|")
                || message.startsWith("/live_p2p_accept|")
                || message.startsWith("/live_p2p_reject|")
                || message.startsWith("/live_p2p_audio|")
                || message.equals("/live_p2p_end")
                || message.startsWith("/voice|")
                || message.startsWith("/image_info|")
                || message.startsWith("/image_chunk|")
                || message.startsWith("/p2p_verify|")
                || message.startsWith("/p2p|")
                || message.startsWith("/deepseek|")
                || message.startsWith("/game_record|")
                || message.startsWith("/game_rank|")
                || message.equals("/mp_list")
                || message.startsWith("/mp_create|")
                 || message.startsWith("/mp_join|")
                 || message.equals("/mp_leave")
                 || message.equals("/mp_world_request")
                 || message.startsWith("/mp_bots|")
                 || message.startsWith("/mp_boss_spawn|")
                 || message.startsWith("/mp_boss_move|")
                 || message.startsWith("/mp_boss_wave|")
                 || message.startsWith("/mp_boss_hit|")
                 || message.startsWith("/mp_boss_attack|")
                 || message.startsWith("/mp_bot_state|")
                 || message.startsWith("/mp_bot_hit|")
                 || message.startsWith("/mp_bot_kill|")
                 || message.startsWith("/mp_bot_attack|")
                 || message.startsWith("/mp_state|")
                 || message.startsWith("/mp_pickup|")
                 || message.startsWith("/mp_death|")
                 || message.startsWith("/mp_hit|")
                || message.startsWith("/mp_melee|");
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isFiniteDouble(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isWebUserActivity(String message) {
        if (message.startsWith("/live_group_audio|")) {
            return containsAudiblePcm(message.substring(18));
        }
        if (message.startsWith("/live_p2p_audio|")) {
            return containsAudiblePcm(message.substring(16));
        }
        return !message.equals("/ping")
                && !message.startsWith("/web_")
                && !message.startsWith("/version|")
                && !message.startsWith("/login|")
                && !message.startsWith("/group|")
                && !message.startsWith("/nickname|");
    }

    private boolean containsAudiblePcm(String encodedAudio) {
        if (encodedAudio.isEmpty() || encodedAudio.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
            return false;
        }
        try {
            byte[] pcm = Base64.getDecoder().decode(encodedAudio);
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                if (Math.abs(sample) >= 820) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private boolean isMutedForSending(String message) {
        if (!server.userManager.isUserMuted(nickname)) {
            return false;
        }
        if (message.startsWith("/version|")
                || message.equals("/ping")
                || message.equals("/live_group_leave")
                || message.equals("/live_p2p_end")
                || message.startsWith("/live_p2p_reject|")) {
            return false;
        }
        return true;
    }
    
    // 清理客户端资源的方法
    void cleanupClient() {
        server.webPan.revoke(webSession);
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            handlerActive = false;
            if (liveAudioWriterThread != null) {
                liveAudioWriterThread.interrupt();
            }
            liveAudioOutboundQueue.clear();
            server.voiceManager.leaveVoiceRoom(this, false);
            server.voiceManager.endP2PVoiceCall(nickname);
            server.voiceManager.clearPendingP2PVoiceRequests(nickname);
            server.voiceManager.handleServerP2PClientUnavailable(nickname);

            // 退出多人游戏服务器（房主退出则删除服务器并通知其他成员）
            server.fpsLobby.leave(nickname);

            // 从群组中移除客户端
            if (group != null && server.groups.containsKey(group)) {
                server.groups.get(group).remove(this);
            }
            server.clientNicknames.remove(clientId);
            server.clientGroups.remove(clientId);
            server.clientLastActiveTime.remove(clientId); // 移除客户端活跃时间记录
            
            // 从在线用户列表中移除
            server.userManager.removeOnlineUser(nickname);
            
            // 清理点对点聊天映射
            String password = server.userP2PPasswords.remove(nickname);
            if (password != null) {
                server.passwordToUser.remove(password);
            }
            server.userHandlers.remove(nickname);
            
            if (transport != null) {
                transport.close();
            }
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.ui.refreshOnlineUsersPanel();
            server.ui.refreshWebControlState();
        } catch (IOException e) {
            server.log("关闭客户端连接时出错: " + e.getMessage());
        } finally {
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.ui.refreshWebControlState();
        }
    }

    // 发送历史聊天记录给客户端
    private void sendChatHistory() {
        List<ChatMessage> history = server.groupChatHistories.get(group);
        if (history != null && !history.isEmpty()) {
            sendMessage("/history|start"); // 开始发送历史记录标记
            for (ChatMessage record : history) {
                sendMessage("/history|" + record.toString()); // 发送每条历史记录
            }
            sendMessage("/history|end"); // 结束发送历史记录标记
        }
    }

    // 发送消息给当前客户端
    public void sendMessage(String msg) {
        if (msg.startsWith("/live_group_audio|") || msg.startsWith("/live_p2p_audio|")) {
            if (!liveAudioOutboundQueue.offer(msg)) {
                liveAudioOutboundQueue.poll();
                liveAudioOutboundQueue.offer(msg);
            }
            return;
        }
        writeMessageDirectly(msg);
    }

    private void startLiveAudioWriter() {
        liveAudioWriterThread = new Thread(() -> {
            while (handlerActive && socket != null && !socket.isClosed()) {
                try {
                    String message = liveAudioOutboundQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        writeMessageDirectly(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LiveAudioWriter-" + clientId);
        liveAudioWriterThread.setDaemon(true);
        liveAudioWriterThread.start();
    }

    private void writeMessageDirectly(String msg) {
        if (!handlerActive || transport == null) {
            return;
        }
        try {
            transport.sendMessage(msg);
        } catch (IOException e) {
            handlerActive = false;
            server.http.closeQuietly(socket);
        }
    }

    // 获取客户端昵称
    public String getNickname() {
        return nickname;
    }

    String getClientIp() {
        return clientIp;
    }

    String getWebUserId() {
        return webSession == null ? null : webSession.userId;
    }

    private boolean registerConnectionAdmissionAttempt() throws IOException {
        if (!connectionAdmissionChecked.compareAndSet(false, true)) return true;
        IpBanManager.Decision decision = server.ipBanManager.registerConnection(clientIp);
        if (decision == IpBanManager.Decision.ALLOWED) return true;
        server.log("已拒绝 IP " + clientIp + " 的聊天接入尝试：" + decision.message);
        try { sendMessage("IP 已被服务器封禁，无法建立聊天会话"); }
        finally { closeConnection(); }
        return false;
    }

    // 获取客户端群组
    public String getGroup() {
        return group;
    }

    // 在群组内广播普通消息给其他客户端
    private void broadcast(String msg, ClientHandler exclude) {
        List<ClientHandler> groupClients = server.groups.get(group);
        if (groupClients != null) {
            for (ClientHandler client : groupClients) {
                if (client != exclude) {
                    client.sendMessage("[" + nickname + "] " + msg);
                }
            }
        }
    }

    // 在群组内广播特殊格式消息给其他客户端（如语音、图片等）
    private void broadcastSpecialMessage(String message, ClientHandler exclude) {
        List<ClientHandler> groupClients = server.groups.get(group);
        if (groupClients != null) {
            for (ClientHandler client : groupClients) {
                if (client != exclude) {
                    client.sendMessage(message); // 直接转发消息
                }
            }
        }
    }

    // 在群组内广播语音消息给其他客户端
    private void broadcastVoice(String voiceMessage, ClientHandler exclude) {
        broadcastSpecialMessage(voiceMessage, exclude);
    }

    // 关闭客户端连接
    public void closeConnection() throws IOException {
        handlerActive = false;
        server.webPan.revoke(webSession);
        transport.close();
    }
}
