package feixue.chat.server.com;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

public class ChatServer extends JFrame {
    ServerSocket serverSocket; // 修改为普通ServerSocket类型，兼容SSL和普通连接
    SSLServerSocket sslServerSocket;
    volatile SSLContext sslContext;
    volatile boolean sslEnabled;
    volatile int sslPort = 8443;
    volatile String sslCertificateFile = "ssl/cloudflare-origin.pem";
    volatile String sslPrivateKeyFile = "ssl/cloudflare-origin.key";
    Path sslConfigPath;
    // 存储每个群组的客户端列表
    Map<String, List<ClientHandler>> groups = new ConcurrentHashMap<>();
    // 存储客户端ID和昵称的映射
    ConcurrentHashMap<String, String> clientNicknames = new ConcurrentHashMap<>();
    // 存储客户端ID和群组的映射
    ConcurrentHashMap<String, String> clientGroups = new ConcurrentHashMap<>();
    // 存储每个群组的聊天记录
    Map<String, List<ChatMessage>> groupChatHistories = new ConcurrentHashMap<>();
    // 存储每个群组最近10分钟内的消息，用于检测重复消息
    Map<String, List<RecentMessage>> recentMessages = new ConcurrentHashMap<>();
    // 存储在线用户名
    Set<String> onlineUsers = ConcurrentHashMap.newKeySet();
    // 存储被禁止的用户
    Set<String> bannedUsers = ConcurrentHashMap.newKeySet();
    // 用户名 -> 禁言结束时间戳
    Map<String, Long> mutedUsers = new ConcurrentHashMap<>();
    // 点对点聊天密码映射
    Map<String, String> userP2PPasswords = new ConcurrentHashMap<>(); // 用户名 -> 密码
    Map<String, String> passwordToUser = new ConcurrentHashMap<>(); // 密码 -> 用户名
    // 用户名到ClientHandler的映射
    Map<String, ClientHandler> userHandlers = new ConcurrentHashMap<>();
    Set<ClientHandler> allClientHandlers = ConcurrentHashMap.newKeySet();
    Set<ClientHandler> webClientHandlers = ConcurrentHashMap.newKeySet();
    // 每个频道的实时语音成员
    Map<String, Set<ClientHandler>> voiceRooms = new ConcurrentHashMap<>();
    // 每个频道按发送者保存短音频队列，避免网络成批到达时覆盖前一帧
    Map<String, Map<ClientHandler, BlockingQueue<byte[]>>> voiceRoomAudioQueues = new ConcurrentHashMap<>();
    // 服务器端加入频道时也使用短队列，和客户端帧走同一混音节奏
    Map<String, BlockingQueue<byte[]>> serverVoiceAudioQueues = new ConcurrentHashMap<>();
    Map<String, Boolean> voiceChannelEnabled = new ConcurrentHashMap<>();
    // 每个频道独立的实时语音音量倍率，默认保持原声 x1。
    Map<String, Integer> voiceChannelVolumeGain = new ConcurrentHashMap<>();
    final AtomicBoolean voiceChannelRefreshPending = new AtomicBoolean(false);
    final Object serverVoiceLock = new Object();
    volatile String serverVoiceGroup;
    volatile boolean serverVoiceStarting;
    TargetDataLine serverVoiceTargetLine;
    SourceDataLine serverVoiceSourceLine;
    Thread serverVoiceCaptureThread;
    Thread serverVoicePlaybackThread;
    final BlockingQueue<byte[]> serverVoicePlaybackQueue = new ArrayBlockingQueue<>(50);
    static final AudioFormat SERVER_VOICE_FORMAT = new AudioFormat(8000.0f, 16, 1, true, false);
    static final int SERVER_VOICE_CHUNK_BYTES = 320;
    final Object serverP2PVoiceLock = new Object();
    volatile String serverP2PVoicePeer;
    volatile String serverP2PVoicePendingUser;
    volatile long serverP2PVoicePendingTime;
    volatile boolean serverP2PVoiceStarting;
    TargetDataLine serverP2PVoiceTargetLine;
    SourceDataLine serverP2PVoiceSourceLine;
    Thread serverP2PVoiceCaptureThread;
    Thread serverP2PVoicePlaybackThread;
    final BlockingQueue<byte[]> serverP2PVoicePlaybackQueue = new ArrayBlockingQueue<>(50);
    // 已建立的一对一语音通话，用户名 -> 对方用户名
    Map<String, String> activeP2PVoicePeers = new ConcurrentHashMap<>();
    // 待处理的语音申请，接收方用户名 -> 发起方用户名
    Map<String, String> pendingP2PVoiceRequests = new ConcurrentHashMap<>();
    Map<String, Long> pendingP2PVoiceRequestTimes = new ConcurrentHashMap<>();
    // DeepSeek AI问答服务
    DeepSeekService deepSeekService;
    VoiceManager voiceManager;
    UserManager userManager;
    ServerConsole console;
    HttpFrontend http;
    WebPanService webPan;
    IpBanManager ipBanManager;
    ConfigManager config;
    MessageGuard messageGuard;
    ChatHistoryStore history;
    GameRecordStore gameRecordStore;
    FpsLobbyManager fpsLobby;
    ChatServerUi ui;

    // 私有频道账号、密码和内部群组名均由 onlypd.json 动态加载。
    volatile Map<String, String> accountPasswords = Collections.emptyMap();
    volatile Map<String, String> accountGroups = Collections.emptyMap();
    volatile Set<String> configuredChannelGroups = Collections.emptySet();
    Path onlyPdConfigPath;
    long onlyPdLastModified = Long.MIN_VALUE;
    long onlyPdLastSize = Long.MIN_VALUE;
    java.util.concurrent.ScheduledExecutorService channelConfigMonitor;
    volatile boolean serverStarting;
    volatile boolean webAccessEnabled;
    volatile String webVerificationQuestion = "运营者是谁？";
    volatile String webVerificationAnswer = "Fang";
    Path webConfigPath;
    volatile String minimumClientVersion;
    Path minimumVersionConfigPath;
    static final String SERVER_P2P_NAME = "server";
    static final String SERVER_P2P_PASSWORD = "00000";
    static final String MUTED_USERS_FILE = "muted_users.txt";
    static final String ONLY_PD_CONFIG_FILE = "onlypd.json";
    static final String WEB_CONFIG_FILE = "web-config.json";
    static final String IP_BAN_CONFIG_FILE = "ip-ban-config.json";
    static final String SSL_CONFIG_FILE = "ssl-config.json";
    static final int DEFAULT_SSL_PORT = 8443;
    static final String MINIMUM_VERSION_CONFIG_FILE = "version-config.json";
    static final String DEFAULT_MIN_CLIENT_VERSION = "3.0.4";
    static final String WEB_CLIENT_RESOURCE = "/web-client.html";
    static final long WEB_IDLE_TIMEOUT = 60L * 60L * 1000L;
    static final int WEB_MAX_VERIFY_ATTEMPTS = 5;
    static final int MAX_HTTP_LINE_BYTES = 8192;
    static final int MAX_WEBSOCKET_MESSAGE_BYTES = 2 * 1024 * 1024;
    static final String PUBLIC_CHANNEL_DISPLAY = "公开频道（内置）";
    static final String EMPTY_ONLY_PD_CONFIG = "new{\nname@\npassworld@\n};\n";
    static final Pattern CHANNEL_BLOCK_PATTERN = Pattern.compile(
            "(?is)new\\s*\\{(.*?)\\}\\s*;?");
    static final Pattern CHANNEL_FIELD_PATTERN = Pattern.compile(
            "(?im)^\\s*(name|passworld|password)\\s*@\\s*(.*?)\\s*$");
    static final Pattern WEB_QUESTION_PATTERN = Pattern.compile(
            "\"question\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern WEB_ANSWER_PATTERN = Pattern.compile(
            "\"answer\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern SSL_ENABLED_PATTERN = Pattern.compile(
            "\"enabled\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    static final Pattern SSL_PORT_PATTERN = Pattern.compile(
            "\"port\"\\s*:\\s*(\\d+)");
    static final Pattern SSL_CERTIFICATE_PATTERN = Pattern.compile(
            "\"certificateFile\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern SSL_PRIVATE_KEY_PATTERN = Pattern.compile(
            "\"privateKeyFile\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern MINIMUM_VERSION_CONFIG_PATTERN = Pattern.compile(
            "\"minimumClientVersion\"\\s*:\\s*\"([^\"]*)\"");
    static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+){0,3}");

    // 消息字节限制（降低为300）
    static final int MAX_MESSAGE_BYTES = 300;
    // 用户ID字节限制
    static final int MAX_USER_ID_BYTES = 30;
    // 重复消息检测时间窗口（10分钟）
    static final long MESSAGE_DUPLICATE_WINDOW = 10 * 60 * 1000; // 10分钟
    // 连续相同字符限制
    static final int MAX_CONSECUTIVE_SAME_CHARS = 5;
    // 单个实时音频块的Base64长度上限，防止异常客户端占用过多内存和带宽
    static final int MAX_LIVE_AUDIO_BASE64_LENGTH = 1024;
    static final long P2P_VOICE_REQUEST_TIMEOUT = 30000;
    static final int LIVE_AUDIO_CHUNK_BYTES = 320;
    static final int GROUP_AUDIO_INPUT_QUEUE_CAPACITY = 10;
    static final int GROUP_AUDIO_TARGET_BUFFER_FRAMES = 6;
    static final int GROUP_AUDIO_MAX_PLAYBACK_FRAMES = 15;
    static final int MIN_VOICE_VOLUME_GAIN = 1;
    static final int MAX_VOICE_VOLUME_GAIN = 6;

    // 公共频道群组名
    static final String PUBLIC_CHANNEL_GROUP = "group_public";
    
    // 违禁词列表（从pbc.txt加载，若文件为空则使用默认值）
    Set<String> forbiddenWords = new HashSet<>(Arrays.asList(
        "hello"
    ));

    // 图片保存根目录
    static final String IMAGE_SAVE_DIR = "onlyph";

    // 存储每个群组的图片接收器
    Map<String, ImageChunkReceiver> imageReceivers = new ConcurrentHashMap<>();

    // 添加客户端连接状态检查相关字段
    Map<String, Long> clientLastActiveTime = new ConcurrentHashMap<>(); // 存储客户端最后活跃时间
    static final long CLIENT_TIMEOUT = 100000; // 30秒超时
    volatile boolean isRunning = true; // 服务器运行状态

    public ChatServer() {
        config = new ConfigManager(this); // 初始化配置管理
        try {
            webPan = new WebPanService(config.resolveConfigPath("webpan.properties"));
            ipBanManager = new IpBanManager(config.resolveConfigPath(IP_BAN_CONFIG_FILE));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("无法加载服务器访问配置", e);
        }
        messageGuard = new MessageGuard(this); // 初始化消息校验（配置加载会用到版本校验）
        config.loadOnlyPdConfiguration(true);
        config.loadWebConfiguration();
        config.loadMinimumVersionConfiguration();
        config.loadSslConfiguration();
        // 初始化各功能模块（界面构建会用到）
        history = new ChatHistoryStore(this); // 初始化聊天记录存储
        gameRecordStore = new GameRecordStore(this); // 初始化小游戏战绩记录存储
        fpsLobby = new FpsLobbyManager(this); // 初始化射击生存多人模式大厅
        userManager = new UserManager(this); // 初始化用户管理
        webPan.setUserDisconnecter(userManager::disconnectAllConnectionsForUser);
        voiceManager = new VoiceManager(this); // 初始化语音管理
        console = new ServerConsole(this); // 初始化服务器控制台
        http = new HttpFrontend(this); // 初始化网页前端
        deepSeekService = new DeepSeekService(); // 初始化DeepSeek服务
        ui = new ChatServerUi(this); // 初始化服务器界面
        ui.buildUi();
        history.loadChatHistory(); // 启动时加载聊天记录
        userManager.loadBannedUsers(); // 启动时加载禁止用户列表
        userManager.loadMutedUsers(); // 启动时加载禁言用户列表
        userManager.loadOnlineUsers(); // 启动时加载在线用户列表
        messageGuard.loadForbiddenWords(); // 启动时加载屏蔽词列表
        startChannelConfigMonitor();
    }

    void startChannelConfigMonitor() {
        channelConfigMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChannelConfigMonitor");
            thread.setDaemon(true);
            return thread;
        });
        channelConfigMonitor.scheduleWithFixedDelay(
                () -> config.loadOnlyPdConfiguration(false), 1500, 1500, TimeUnit.MILLISECONDS);
    }


    // 保存图片到本地文件系统
    void saveImageToFile(ImageChunkReceiver receiver) {
        try {
            String completeImageData = receiver.getCompleteImageData();
            byte[] imageBytes = Base64.getDecoder().decode(completeImageData);

            // 创建群组目录
            File groupDir = new File(IMAGE_SAVE_DIR + File.separator + receiver.getGroup());
            if (!groupDir.exists()) {
                groupDir.mkdirs();
            }

            // 生成文件名：时间戳_发送者_原文件名
            String fileName = System.currentTimeMillis() + "_" + receiver.getSender() + "_" + receiver.getFileName();
            // 确保文件名安全
            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

            File imageFile = new File(groupDir, fileName);

            // 写入图片文件
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(imageBytes);
            }

            log("图片已保存: " + imageFile.getAbsolutePath());
        } catch (Exception e) {
            log("保存图片失败: " + e.getMessage());
        }
    }

    // 清理过期的图片接收器
    void cleanupExpiredImageReceivers() {
        imageReceivers.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired();
            if (expired) {
                log("清理过期图片接收器: " + entry.getValue().getImageId());
            }
            return expired;
        });
    }


    // 在服务器启动后添加定时清理任务
    void startServer() {
        if (serverStarting || (serverSocket != null && !serverSocket.isClosed())) {
            return;
        }
        serverStarting = true;
        ui.startBtn.setEnabled(false);
        ui.refreshChannelManagementState();
        ui.refreshMinimumVersionControlState();
        new Thread(() -> {
            try {
                isRunning = true;
                int port = Integer.parseInt(ui.portField.getText());
                serverSocket = new ServerSocket(port);
                serverStarting = false;
                log("服务器启动成功，监听端口: " + port);
                http.startSslListenerIfConfigured();
                voiceManager.refreshVoiceChannelPanel();
                ui.refreshWebControlState();
                ui.refreshMinimumVersionControlState();
                SwingUtilities.invokeLater(() -> {
                    ui.portField.setEnabled(false);
                    ui.stopBtn.setEnabled(true); // 启用关闭服务器按钮
                    ui.refreshChannelManagementState();
                });
                
                // 清理旧的在线用户列表，确保下次启动后用户可以成功进入聊天
                onlineUsers.clear();
                userManager.saveOnlineUsers();
                ui.refreshOnlineUsersPanel();
                log("已清理旧的在线用户列表");
                
                // 启动定时清理任务
                startCleanupTask();

                // 启动频道实时语音混音任务
                voiceManager.startVoiceMixerTask();
                
                // 启动客户端超时检测任务
                startClientTimeoutCheckTask();
                
                // 启动在线用户列表广播任务
                startOnlineUsersBroadcastTask();

                // 启动网页会话一小时无操作超时检查
                http.startWebIdleTimeoutTask();
                
                while (true) {
                    Socket clientSocket = serverSocket.accept();  // 阻塞等待客户端连接
                    Thread routerThread = new Thread(() -> http.routeIncomingConnection(clientSocket),
                            "ConnectionRouter-" + clientSocket.getRemoteSocketAddress());
                    routerThread.setDaemon(true);
                    routerThread.start();
                }
            } catch (IOException ex) {
                serverStarting = false;
                if (serverSocket != null && !serverSocket.isClosed()) {
                    log("服务器启动失败: " + ex.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    ui.startBtn.setEnabled(true);
                    ui.portField.setEnabled(true);
                    ui.stopBtn.setEnabled(false);
                    ui.refreshChannelManagementState();
                    ui.refreshWebControlState();
                    ui.refreshMinimumVersionControlState();
                });
            } catch (RuntimeException ex) {
                serverStarting = false;
                log("服务器启动失败: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    ui.startBtn.setEnabled(true);
                    ui.portField.setEnabled(true);
                    ui.stopBtn.setEnabled(false);
                    ui.refreshChannelManagementState();
                    ui.refreshWebControlState();
                    ui.refreshMinimumVersionControlState();
                });
            }
        }).start();
    }
    

    // 启动客户端超时检测任务
    void startClientTimeoutCheckTask() {
        final ServerSocket activeServerSocket = serverSocket;
        Thread timeoutCheckThread = new Thread(() -> {
            while (isRunning && serverSocket == activeServerSocket
                    && activeServerSocket != null && !activeServerSocket.isClosed()) {
                try {
                    Thread.sleep(10000); // 每10秒检查一次
                    
                    long currentTime = System.currentTimeMillis();
                    for (Map.Entry<String, Long> entry : new ArrayList<>(clientLastActiveTime.entrySet())) {
                        String clientId = entry.getKey();
                        long lastActiveTime = entry.getValue();
                        
                        // 检查客户端是否超时
                        if (currentTime - lastActiveTime > CLIENT_TIMEOUT) {
                            String nickname = clientNicknames.get(clientId);
                            if (nickname != null) {
                                log("客户端 " + nickname + " (" + clientId + ") 连接超时，已断开");
                                
                                for (ClientHandler handler : new ArrayList<>(allClientHandlers)) {
                                    if (handler.clientId.equals(clientId)) {
                                        handler.cleanupClient();
                                        break;
                                    }
                                }
                                
                                // 从记录中移除客户端
                                clientNicknames.remove(clientId);
                                clientGroups.remove(clientId);
                                clientLastActiveTime.remove(clientId, lastActiveTime);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log("检查客户端超时任务出错: " + e.getMessage());
                }
            }
        });
        timeoutCheckThread.setDaemon(true);
        timeoutCheckThread.start();
    }
    
    // 添加服务器关闭方法
    public void shutdown() {
        isRunning = false; // 设置服务器运行状态为false
        webAccessEnabled = false;
        webPan.setWebEnabled(false);
        voiceManager.stopServerVoiceSession();
        voiceManager.stopServerP2PVoiceSession(true);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (sslServerSocket != null && !sslServerSocket.isClosed()) {
                sslServerSocket.close();
            }
        } catch (IOException e) {
            log("关闭服务器Socket时出错: " + e.getMessage());
        }
    }
    
    // 停止服务器并清理所有客户端
    void stopServer() {
        log("正在停止服务器...");
        ui.disableWebAccess(false);
        // Temporary connection-rate bans only protect one running server session.
        ipBanManager.clearTemporaryBans();
        
        // 清理所有客户端连接
        disconnectAllClients();
        voiceManager.stopServerVoiceSession();
        
        // 关闭服务器socket
        shutdown();
        
        // 清理所有数据
        clearAllServerData();
        
        // 重置UI状态
        SwingUtilities.invokeLater(() -> {
            serverStarting = false;
            ui.startBtn.setEnabled(true);
            ui.portField.setEnabled(true);
            ui.stopBtn.setEnabled(false);
            voiceManager.refreshVoiceChannelPanel();
            ui.refreshChannelManagementState();
            ui.refreshWebControlState();
            ui.refreshMinimumVersionControlState();
        });
        
        log("服务器已停止，所有客户端连接已断开，数据已清理");
    }
    
    // 清理所有客户端连接
    void disconnectAllClients() {
        List<ClientHandler> clients = new ArrayList<>(allClientHandlers);
        int clientCount = clients.size();
        for (ClientHandler client : clients) {
            try {
                client.sendMessage("/server_shutdown|服务器正在关闭，请重新连接");
                client.closeConnection();
            } catch (IOException e) {
                // 忽略关闭错误
            }
        }
        
        // 清空所有映射
        groups.clear();
        clientNicknames.clear();
        clientGroups.clear();
        userHandlers.clear();
        userP2PPasswords.clear();
        passwordToUser.clear();
        voiceRooms.clear();
        voiceRoomAudioQueues.clear();
        activeP2PVoicePeers.clear();
        pendingP2PVoiceRequests.clear();
        pendingP2PVoiceRequestTimes.clear();
        clientLastActiveTime.clear(); // 清理客户端活跃时间记录
        allClientHandlers.clear();
        webClientHandlers.clear();
        synchronized (serverP2PVoiceLock) {
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
        }
        ui.refreshOnlineUsersPanel();
        
        log("已断开 " + clientCount + " 个客户端连接");
    }
    
    // 清理所有服务器数据
    void clearAllServerData() {
        // 清空在线用户列表
        onlineUsers.clear();
        userManager.saveOnlineUsers(); // 更新文件
        
        // 清空被禁止用户列表
        bannedUsers.clear();
        userManager.saveBannedUsers();
        
        // 清空最近消息记录
        recentMessages.clear();
        
        // 清空图片接收器
        imageReceivers.clear();
        voiceRooms.clear();
        voiceRoomAudioQueues.clear();
        activeP2PVoicePeers.clear();
        pendingP2PVoiceRequests.clear();
        pendingP2PVoiceRequestTimes.clear();
        synchronized (serverP2PVoiceLock) {
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
        }
        ui.refreshOnlineUsersPanel();
        
        // 清空客户端活跃时间记录（已在disconnectAllClients中清理，这里再次确保）
        clientLastActiveTime.clear();
        
        log("所有服务器数据已清理");
    }

    // 启动定时清理任务
    void startCleanupTask() {
        final ServerSocket activeServerSocket = serverSocket;
        Thread cleanupThread = new Thread(() -> {
            while (isRunning && serverSocket == activeServerSocket
                    && activeServerSocket != null && !activeServerSocket.isClosed()) {
                try {
                    Thread.sleep(10000); // 每10秒清理一次
                    androidtupian.cleanupExpiredReceivers(new androidtupian.LogCallback() {
                        @Override
                        public void log(String message) {
                            ChatServer.this.log(message);
                        }
                    });
                    voiceManager.cleanupExpiredP2PVoiceRequests();
                    voiceManager.cleanupExpiredServerP2PVoiceRequest();
                    userManager.cleanupExpiredMutedUsers();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    // 定时广播在线用户列表
    void startOnlineUsersBroadcastTask() {
        final ServerSocket activeServerSocket = serverSocket;
        Thread broadcastThread = new Thread(() -> {
            // 立即广播一次
            userManager.broadcastOnlineUsers();
            while (isRunning && serverSocket == activeServerSocket
                    && activeServerSocket != null && !activeServerSocket.isClosed()) {
                try {
                    Thread.sleep(30000); // 每30秒广播一次
                    userManager.broadcastOnlineUsers();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log("广播在线用户列表时出错: " + e.getMessage());
                }
            }
        });
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    // 日志输出方法
    void log(String text) {
        SwingUtilities.invokeLater(() -> {
            ui.logArea.append(text + "\n");
            ui.logArea.setCaretPosition(ui.logArea.getDocument().getLength());  // 自动滚动到底部
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatServer().setVisible(true));
    }
}
