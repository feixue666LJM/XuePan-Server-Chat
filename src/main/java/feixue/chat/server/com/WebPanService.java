package feixue.chat.server.com;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashMap;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class WebPanService {
    static final int MAX_ACTIVE_DOWNLOADS = 50;
    /** Maximum aggregate size of files reserved for active transfers (7 GiB). */
    static final long MAX_ACTIVE_DOWNLOAD_BYTES = 7L * 1024L * 1024L * 1024L;
    static final int MAX_BATCH_FILES = 3;
    static final long DEFAULT_DOWNLOAD_INTERVAL_MS = 30_000L;
    static final long BURST_DOWNLOAD_INTERVAL_MS = 5 * 60_000L;
    static final long BURST_PENALTY_DURATION_MS = 60 * 60_000L;
    static final int RAPID_REQUEST_THRESHOLD = 10;
    static final long CAPTCHA_LIFETIME_MS = 5 * 60_000L;
    private final Path configPath;
    private final LongSupplier clock;
    private volatile Semaphore downloadSlots;
    private volatile int maxActiveDownloads;
    private volatile long maxActiveDownloadBytes;
    private volatile int maxBatchFiles;
    private final AtomicInteger activeDownloads = new AtomicInteger();
    private final AtomicLong activeDownloadBytes = new AtomicLong();
    private final ConcurrentHashMap<String, FileRateState> fileRateStates = new ConcurrentHashMap<>();
    private Path root = Paths.get(WebPanFiles.DEFAULT_ROOT).toAbsolutePath().normalize();
    private Path captchaDirectory;
    private boolean enabled;
    private boolean webEnabled;
    private static final String COOKIE_NAME = "__Host-feixue-web";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Set<Session> sessions = new HashSet<>();
    private final Map<Socket, Session> transfers = new HashMap<>();
    private UserDisconnecter userDisconnecter = userId -> { };

    interface UserDisconnecter {
        void disconnect(String userId);
    }

    private static final class CaptchaChallenge {
        final String token;
        final Path image;
        final String answer;
        final Set<String> targetFiles;
        final Set<String> downloadedFiles = new LinkedHashSet<>();
        final long expiresAt;
        boolean passed;

        CaptchaChallenge(String token, Path image, String answer, Set<String> targetFiles, long expiresAt) {
            this.token = token;
            this.image = image;
            this.answer = answer;
            this.targetFiles = new LinkedHashSet<>(targetFiles);
            this.expiresAt = expiresAt;
        }
    }

    // State is stored per real file path. A restart intentionally resets this in-memory protection.
    static final class FileRateState {
        long lastRequestAt;
        int consecutiveRequests;
        long burstStartedAt;
    }

    static final class DownloadAdmission implements AutoCloseable {
        private final WebPanService service;
        private final int retryAfterSeconds;
        private final String message;
        private final int rejectionStatus;
        private final boolean html;
        private final Runnable afterResponse;
        private final long reservedBytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private DownloadAdmission(WebPanService service) {
            this.service = service;
            this.retryAfterSeconds = 0;
            this.message = "";
            this.rejectionStatus = 0;
            this.html = false;
            this.afterResponse = null;
            this.reservedBytes = 0;
        }

        private DownloadAdmission(int retryAfterSeconds, String message) {
            this(retryAfterSeconds, message, 429, false, null);
        }

        private DownloadAdmission(int retryAfterSeconds, String message, int rejectionStatus,
                                  boolean html, Runnable afterResponse) {
            this.service = null;
            this.retryAfterSeconds = retryAfterSeconds;
            this.message = message;
            this.rejectionStatus = rejectionStatus;
            this.html = html;
            this.afterResponse = afterResponse;
            this.reservedBytes = 0;
        }

        private DownloadAdmission(WebPanService service, long reservedBytes) {
            this.service = service;
            this.retryAfterSeconds = 0;
            this.message = "";
            this.rejectionStatus = 0;
            this.html = false;
            this.afterResponse = null;
            this.reservedBytes = reservedBytes;
        }

        boolean isAllowed() { return service != null; }
        int getRetryAfterSeconds() { return retryAfterSeconds; }
        String getMessage() { return message; }
        int getRejectionStatus() { return rejectionStatus; }
        boolean isHtml() { return html; }
        void afterResponse() { if (afterResponse != null) afterResponse.run(); }

        @Override public void close() {
            if (service != null && released.compareAndSet(false, true)) {
                service.releaseDownloadSlot(reservedBytes);
            }
        }
    }

    static final class Session {
        final String token;
        boolean revoked;
        BooleanSupplier verified = () -> false;
        String userId;
        final Map<String, CaptchaChallenge> captchaChallenges = new HashMap<>();
        Session(String token) { this.token = token; }
        String cookie() { return COOKIE_NAME + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict"; }
    }

    synchronized Session newSession(String cookie) {
        Session existing = findSession(cookie);
        if (existing != null) return existing;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    synchronized void authorize(Session session, BooleanSupplier verified) {
        if (session == null || session.revoked || !webEnabled) return;
        session.verified = verified;
        sessions.add(session);
    }

    synchronized void revoke(Session session) {
        if (session == null) return;
        session.revoked = true;
        sessions.remove(session);
        java.util.Iterator<Map.Entry<Socket, Session>> iterator = transfers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Socket, Session> entry = iterator.next();
            if (entry.getValue() == session) {
                try { entry.getKey().close(); } catch (IOException ignored) { }
                iterator.remove();
            }
        }
    }

    private Session findSession(String cookie) {
        if (cookie == null || cookie.length() > 8192) return null;
        String token = null;
        for (String part : cookie.split(";")) {
            String value = part.trim();
            if (value.startsWith(COOKIE_NAME + "=")) {
                if (token != null) return null;
                token = value.substring(COOKIE_NAME.length() + 1);
            }
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        for (Session session : sessions) {
            if (!session.revoked && session.token.equals(token) && session.verified.getAsBoolean()) return session;
        }
        return null;
    }

    WebPanService(Path configPath) throws IOException {
        this(configPath, System::currentTimeMillis, MAX_ACTIVE_DOWNLOADS);
    }

    WebPanService(Path configPath, LongSupplier clock, int maxActiveDownloads) throws IOException {
        if (clock == null || maxActiveDownloads < 1) throw new IllegalArgumentException("Invalid download limiter configuration");
        this.configPath = configPath;
        this.clock = clock;
        this.maxActiveDownloads = maxActiveDownloads;
        this.maxActiveDownloadBytes = MAX_ACTIVE_DOWNLOAD_BYTES;
        this.maxBatchFiles = MAX_BATCH_FILES;
        if (Files.isRegularFile(configPath)) {
            Properties settings = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) { settings.load(in); }
            root = Paths.get(settings.getProperty("root", root.toString())).toAbsolutePath().normalize();
            String captchaPath = settings.getProperty("captchaDirectory", "").trim();
            if (!captchaPath.isEmpty()) captchaDirectory = Paths.get(captchaPath).toAbsolutePath().normalize();
            enabled = Boolean.parseBoolean(settings.getProperty("enabled", "false"));
            this.maxActiveDownloads = readIntSetting(settings, "maxActiveDownloads", maxActiveDownloads, 1, 1000);
            this.maxActiveDownloadBytes = readGiBSetting(settings, "maxActiveDownloadGiB", MAX_ACTIVE_DOWNLOAD_BYTES);
            this.maxBatchFiles = readIntSetting(settings, "maxBatchFiles", MAX_BATCH_FILES, 1, 20);
        }
        this.downloadSlots = new Semaphore(this.maxActiveDownloads, true);
    }

    synchronized Path getRoot() { return root; }
    synchronized Path getCaptchaDirectory() { return captchaDirectory; }
    synchronized boolean isEnabled() { return enabled; }
    synchronized boolean isAvailable() {
        return enabled && webEnabled && Files.isDirectory(root) && isCaptchaAvailable();
    }
    synchronized boolean isCaptchaAvailable() { return findCaptchaImages(captchaDirectory).size() > 0; }
    int getActiveDownloadCount() { return activeDownloads.get(); }
    long getActiveDownloadBytes() { return activeDownloadBytes.get(); }
    int getMaxActiveDownloads() { return maxActiveDownloads; }
    long getMaxActiveDownloadBytes() { return maxActiveDownloadBytes; }
    int getMaxBatchFiles() { return maxBatchFiles; }

    DownloadAdmission tryAcquireDownload(Path file) {
        final long now = clock.getAsLong();
        final String fileKey;
        try {
            fileKey = file.toRealPath().toString();
        } catch (IOException e) {
            return new DownloadAdmission(0, "文件不可用，请稍后重试");
        }
        final int[] retryAfterSeconds = new int[1];
        fileRateStates.compute(fileKey, (key, state) -> {
            FileRateState next = state == null ? new FileRateState() : state;
            if (next.burstStartedAt > 0 && now - next.burstStartedAt >= BURST_PENALTY_DURATION_MS) {
                next.lastRequestAt = 0;
                next.consecutiveRequests = 0;
                next.burstStartedAt = 0;
            }
            if (next.burstStartedAt == 0 && next.lastRequestAt > 0
                    && now - next.lastRequestAt >= DEFAULT_DOWNLOAD_INTERVAL_MS) {
                // A quiet 30-second gap ends the rapid-request sequence.
                next.consecutiveRequests = 0;
            }
            boolean entersBurst = next.burstStartedAt == 0
                    && next.consecutiveRequests >= RAPID_REQUEST_THRESHOLD;
            long interval = entersBurst || next.burstStartedAt > 0
                    ? BURST_DOWNLOAD_INTERVAL_MS : DEFAULT_DOWNLOAD_INTERVAL_MS;
            long elapsed = next.lastRequestAt == 0 ? interval : Math.max(0, now - next.lastRequestAt);
            retryAfterSeconds[0] = elapsed >= interval ? 0
                    : (int) Math.max(1, (interval - elapsed + 999L) / 1000L);
            next.lastRequestAt = now;
            next.consecutiveRequests++;
            if (entersBurst) next.burstStartedAt = now;
            return next;
        });
        if (retryAfterSeconds[0] > 0) {
            return new DownloadAdmission(retryAfterSeconds[0], "请求过于频繁，请稍后重试");
        }
        if (!downloadSlots.tryAcquire()) {
            return new DownloadAdmission(1, "并发已满，请稍后重试");
        }
        final long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            downloadSlots.release();
            return new DownloadAdmission(0, "文件不可用，请稍后重试");
        }
        if (size < 0 || size > maxActiveDownloadBytes) {
            downloadSlots.release();
            return new DownloadAdmission(1, overloadPage(), 503, true, null);
        }
        while (true) {
            long current = activeDownloadBytes.get();
            if (current > maxActiveDownloadBytes - size
                    || !activeDownloadBytes.compareAndSet(current, current + size)) {
                if (current > maxActiveDownloadBytes - size) {
                    downloadSlots.release();
                    return new DownloadAdmission(1, overloadPage(), 503, true, null);
                }
                continue;
            }
            break;
        }
        activeDownloads.incrementAndGet();
        return new DownloadAdmission(this, size);
    }

    private void releaseDownloadSlot(long bytes) {
        activeDownloads.decrementAndGet();
        activeDownloadBytes.addAndGet(-bytes);
        downloadSlots.release();
    }

    synchronized void configure(String directory, boolean allow) throws IOException {
        configure(directory, captchaDirectory == null ? "" : captchaDirectory.toString(), allow,
                maxActiveDownloads, maxActiveDownloadBytes, maxBatchFiles);
    }

    synchronized void configure(String directory, String captchaFolder, boolean allow) throws IOException {
        configure(directory, captchaFolder, allow, maxActiveDownloads, maxActiveDownloadBytes, maxBatchFiles);
    }

    synchronized void configure(String directory, String captchaFolder, boolean allow,
                                 int requestedMaxDownloads, long requestedMaxBytes, int requestedMaxBatch)
            throws IOException {
        if (directory.trim().isEmpty()) throw new IOException("请选择共享目录");
        validateLimits(requestedMaxDownloads, requestedMaxBytes, requestedMaxBatch);
        if (enabled && allow && (requestedMaxDownloads != maxActiveDownloads
                || requestedMaxBytes != maxActiveDownloadBytes || requestedMaxBatch != maxBatchFiles)) {
            throw new IOException("网盘总开关开启时不能修改下载限制，请先关闭网盘");
        }
        if (activeDownloads.get() != 0 && (requestedMaxDownloads != maxActiveDownloads
                || requestedMaxBytes != maxActiveDownloadBytes || requestedMaxBatch != maxBatchFiles)) {
            throw new IOException("仍有下载正在进行，请等待下载结束后再修改下载限制");
        }
        Path next = Paths.get(directory.trim()).toAbsolutePath().normalize();
        Path nextCaptcha = captchaFolder == null || captchaFolder.trim().isEmpty() ? null
                : Paths.get(captchaFolder.trim()).toAbsolutePath().normalize();
        if (allow && (!Files.isDirectory(next) || !Files.isReadable(next))) {
            throw new IOException("共享目录不存在或无法读取：" + next);
        }
        if (allow && findCaptchaImages(nextCaptcha).isEmpty()) {
            throw new IOException("验证图片目录必须存在，并至少包含一张可读取的 .png 图片");
        }
        Properties settings = new Properties();
        settings.setProperty("root", next.toString());
        settings.setProperty("captchaDirectory", nextCaptcha == null ? "" : nextCaptcha.toString());
        settings.setProperty("enabled", Boolean.toString(allow));
        settings.setProperty("maxActiveDownloads", Integer.toString(requestedMaxDownloads));
        settings.setProperty("maxActiveDownloadGiB", Long.toString(requestedMaxBytes / (1024L * 1024L * 1024L)));
        settings.setProperty("maxBatchFiles", Integer.toString(requestedMaxBatch));
        Path parent = configPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "webpan-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { settings.store(out, "Feixue WebPan"); }
            try { Files.move(temp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
        closeTransfers();
        root = next;
        captchaDirectory = nextCaptcha;
        enabled = allow;
        maxActiveDownloads = requestedMaxDownloads;
        maxActiveDownloadBytes = requestedMaxBytes;
        maxBatchFiles = requestedMaxBatch;
        downloadSlots = new Semaphore(maxActiveDownloads, true);
    }

    private static void validateLimits(int downloads, long bytes, int batch) throws IOException {
        if (downloads < 1 || downloads > 1000) throw new IOException("同时下载线程数必须在1至1000之间");
        if (bytes < 1L * 1024L * 1024L * 1024L || bytes > 1024L * 1024L * 1024L * 1024L)
            throw new IOException("活跃下载容量必须在1至1024 GiB之间");
        if (batch < 1 || batch > 20) throw new IOException("最多选择文件数必须在1至20之间");
    }

    private static int readIntSetting(Properties settings, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)).trim());
            return value >= min && value <= max ? value : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    private static long readGiBSetting(Properties settings, String key, long fallback) {
        try {
            long gib = Long.parseLong(settings.getProperty(key, "7").trim());
            return gib >= 1 && gib <= 1024 ? gib * 1024L * 1024L * 1024L : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    synchronized void setUserDisconnecter(UserDisconnecter value) {
        userDisconnecter = value == null ? userId -> { } : value;
    }

    synchronized void bindUser(Session session, String userId) {
        if (session == null || session.revoked || userId == null || userId.trim().isEmpty()) return;
        session.userId = userId;
    }

    private static List<Path> findCaptchaImages(Path directory) {
        List<Path> images = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory) || !Files.isReadable(directory)) return images;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path image : stream) {
                String name = image.getFileName().toString();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") || !isPng(image)) continue;
                images.add(image.toRealPath());
            }
        } catch (IOException ignored) { }
        return images;
    }

    private static boolean isPng(Path image) {
        if (!Files.isRegularFile(image) || !Files.isReadable(image)) return false;
        byte[] signature = new byte[8];
        try (InputStream in = Files.newInputStream(image)) {
            if (in.read(signature) != signature.length) return false;
            return signature[0] == (byte) 0x89 && signature[1] == 0x50 && signature[2] == 0x4e
                    && signature[3] == 0x47 && signature[4] == 0x0d && signature[5] == 0x0a
                    && signature[6] == 0x1a && signature[7] == 0x0a;
        } catch (IOException ignored) {
            return false;
        }
    }

    private String newCaptchaToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private synchronized DownloadAdmission requireCaptcha(Session session, WebPanExchange exchange, Path file) {
        if (session.userId == null || session.userId.isEmpty()) {
            return new DownloadAdmission(0, "<main class=\"wrap\"><h1>请先完成服务器登录</h1></main>", 403, true, null);
        }
        Map<String, String> query = WebPanFiles.parseQuery(exchange.getRequestURI().getRawQuery());
        String token = query.get("captcha");
        String answer = query.get("answer");
        String fileKey;
        try {
            fileKey = file.toRealPath().toString();
        } catch (IOException e) {
            return new DownloadAdmission(0, "文件不可用，请稍后重试", 404, false, null);
        }
        CaptchaChallenge challenge = token == null ? null : session.captchaChallenges.get(token);
        long now = clock.getAsLong();
        if (challenge != null && now >= challenge.expiresAt) {
            session.captchaChallenges.remove(token);
            challenge = null;
        }
        if (challenge != null && !challenge.targetFiles.equals(java.util.Collections.singleton(fileKey))) challenge = null;
        if (challenge != null && answer != null) {
            if (challenge.answer.equals(answer)) {
                challenge.passed = true;
                return new DownloadAdmission(0, captchaRedirect(exchange, token), 303, false, null);
            }
            session.captchaChallenges.remove(token);
            final String failedUser = session.userId;
            return new DownloadAdmission(0, "<main class=\"wrap\"><h1>验证答案错误，已断开所有连接</h1></main>",
                    403, true, () -> failCaptcha(session, failedUser));
        }
        if (challenge != null && challenge.passed) {
            session.captchaChallenges.remove(token);
            return tryAcquireDownload(file);
        }
        List<Path> images = findCaptchaImages(captchaDirectory);
        if (images.isEmpty()) {
            return new DownloadAdmission(0, "<main class=\"wrap\"><h1>验证图片不可用，下载已拒绝</h1></main>", 503, true, null);
        }
        Path image = images.get(RANDOM.nextInt(images.size()));
        String imageName = image.getFileName().toString();
        String expected = imageName.substring(0, imageName.length() - 4);
        String newToken = newCaptchaToken();
        session.captchaChallenges.clear();
        session.captchaChallenges.put(newToken, new CaptchaChallenge(newToken, image, expected,
                java.util.Collections.singleton(fileKey),
                now + CAPTCHA_LIFETIME_MS));
        // This is a normal document navigation, not an HTTP download failure.
        return new DownloadAdmission(0, captchaPage(exchange, newToken), 200, true, null);
    }

    private String captchaRedirect(WebPanExchange exchange, String token) {
        String rawPath = exchange.getRequestURI().getRawPath();
        Map<String, String> query = WebPanFiles.parseQuery(exchange.getRequestURI().getRawQuery());
        StringBuilder location = new StringBuilder("/webpan").append(rawPath).append("?captcha=").append(token);
        if ("1".equals(query.get("download")) || "1".equals(query.get("dl"))) location.append("&download=1");
        return location.toString();
    }

    private String captchaPage(WebPanExchange exchange, String token) {
        // Keep the form action free of a query string. Browsers rebuild a GET form
        // query from its controls, so putting the token in both places can lose or
        // duplicate the challenge parameters inside the sandboxed iframe.
        String action = "/webpan" + exchange.getRequestURI().getRawPath();
        Map<String, String> query = WebPanFiles.parseQuery(exchange.getRequestURI().getRawQuery());
        return WebPanFiles.htmlPage("下载验证", "<main class=\"wrap\"><h1>下载验证</h1>"
                + "<p>请输入下方图片的文件名（不含 .png，区分大小写）。</p>"
                + "<form method=\"get\" action=\"" + WebPanFiles.esc(action) + "\">"
                + "<p><img src=\"/webpan/_captcha/image?captcha=" + WebPanFiles.esc(token)
                + "\" alt=\"验证图片\" style=\"max-width:100%;max-height:260px\"></p>"
                + "<label>答案 <input name=\"answer\" autocomplete=\"off\" required autofocus></label>"
                + "<input type=\"hidden\" name=\"captcha\" value=\"" + WebPanFiles.esc(token) + "\">"
                + ("1".equals(query.get("download")) || "1".equals(query.get("dl"))
                    ? "<input type=\"hidden\" name=\"download\" value=\"1\">" : "")
                + "<button class=\"btn\" type=\"submit\">验证并下载</button></form></main>");
    }

    private void failCaptcha(Session session, String userId) {
        revoke(session);
        userDisconnecter.disconnect(userId);
    }

    private synchronized void serveCaptchaImage(WebPanExchange exchange, Session session) throws IOException {
        String token = WebPanFiles.parseQuery(exchange.getRequestURI().getRawQuery()).get("captcha");
        CaptchaChallenge challenge = token == null ? null : session.captchaChallenges.get(token);
        if (challenge == null || clock.getAsLong() >= challenge.expiresAt || !isPng(challenge.image)) {
            if (token != null) session.captchaChallenges.remove(token);
            WebPanFiles.sendText(exchange, 404, "验证图片不存在或已过期", "HEAD".equalsIgnoreCase(exchange.getRequestMethod()));
            return;
        }
        long size = Files.size(challenge.image);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, size);
        try (InputStream in = Files.newInputStream(challenge.image); OutputStream out = exchange.getResponseBody()) {
            WebPanFiles.copy(in, out, size);
        }
    }

    synchronized void setWebEnabled(boolean value) {
        webEnabled = value;
        if (!value) {
            for (Session session : sessions) session.revoked = true;
            sessions.clear();
            closeTransfers();
        }
    }

    private void closeTransfers() {
        for (Socket socket : transfers.keySet()) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        transfers.clear();
    }

    void handle(Socket socket, String method, String target, Map<String, String> headers) throws IOException {
        handle(socket, method, target, headers, socket.getInetAddress().getHostAddress());
    }

    void handle(Socket socket, String method, String target, Map<String, String> headers, String clientIp) throws IOException {
        WebPanExchange exchange;
        try {
            URI uri = URI.create(target);
            String path = uri.getRawPath();
            if (!"/webpan".equals(path) && !path.startsWith("/webpan/")) throw new IllegalArgumentException();
            String relative = path.substring("/webpan".length());
            if (relative.isEmpty()) relative = "/";
            if (uri.getRawQuery() != null) relative += "?" + uri.getRawQuery();
            exchange = new WebPanExchange(socket, method, URI.create("http://localhost" + relative), headers, clientIp);
        } catch (IllegalArgumentException e) {
            exchange = new WebPanExchange(socket, method, URI.create("/"), headers, clientIp);
            try { WebPanFiles.sendText(exchange, 400, "Invalid request", "HEAD".equals(method)); }
            finally { exchange.close(); }
            return;
        }
        Path publishedRoot;
        Session authorized;
        synchronized (this) {
            authorized = findSession(headers.get("cookie"));
            publishedRoot = isAvailable() ? root : null;
            if (authorized != null && publishedRoot != null) transfers.put(socket, authorized);
        }
        if (authorized == null) {
            try {
                if ("iframe".equals(headers.get("sec-fetch-dest"))) {
                    WebPanFiles.sendHtml(exchange, 401, WebPanFiles.htmlPage("需要验证",
                            "<main id=\"webPanVerificationRequired\" class=\"wrap\"><h1>请先完成网页验证</h1>"
                            + "<a href=\"/?webpan=1\" target=\"_top\">前往验证</a></main>"), "HEAD".equals(method));
                } else {
                    exchange.getResponseHeaders().set("Location", "/?webpan=1");
                    exchange.sendResponseHeaders(303, -1);
                }
            } finally { exchange.close(); }
            return;
        }
        if (publishedRoot == null) {
            try {
                WebPanFiles.sendHtml(exchange, 503, WebPanFiles.htmlPage("肥雪网盘",
                        "<main class=\"wrap\"><h1>肥雪网盘暂未开放</h1><a href=\"/\">返回聊天</a></main>"),
                        "HEAD".equals(method));
            } finally { exchange.close(); }
            return;
        }
        try {
            if ("/_captcha/image".equals(exchange.getRequestURI().getRawPath())) {
                serveCaptchaImage(exchange, authorized);
            } else if ("/_batch".equals(exchange.getRequestURI().getRawPath())) {
                handleBatchDownload(exchange, authorized, publishedRoot);
            } else {
                WebPanFiles.handle(exchange, publishedRoot,
                        (request, file) -> requireCaptcha(authorized, (WebPanExchange) request, file), maxBatchFiles);
            }
        }
        finally {
            exchange.close();
            synchronized (this) { transfers.remove(socket); }
        }
    }

    private void handleBatchDownload(WebPanExchange exchange, Session session, Path publishedRoot)
            throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            WebPanFiles.sendText(exchange, 405, "405 只支持 GET", false);
            return;
        }
        Map<String, List<String>> query = WebPanFiles.parseQueryValues(exchange.getRequestURI().getRawQuery());
        List<String> requested = query.get("file");
        if (requested == null || requested.isEmpty() || requested.size() > maxBatchFiles) {
            WebPanFiles.sendText(exchange, 400, "请选择 1 至 " + maxBatchFiles + " 个文件", false);
            return;
        }

        List<Path> files = new ArrayList<>();
        Set<String> fileKeys = new LinkedHashSet<>();
        long totalBytes = 0;
        for (String value : requested) {
            if (value == null || value.isEmpty()) {
                WebPanFiles.sendText(exchange, 400, "文件选择无效", false);
                return;
            }
            Path file = WebPanFiles.resolve(publishedRoot, value);
            if (file == null || !Files.isRegularFile(file)) {
                WebPanFiles.sendText(exchange, 403, "文件选择无效", false);
                return;
            }
            try {
                String key = file.toRealPath().toString();
                if (!fileKeys.add(key)) {
                    WebPanFiles.sendText(exchange, 400, "不能重复选择同一个文件", false);
                    return;
                }
                files.add(file.toRealPath());
                long size = Files.size(file);
                if (size < 0 || totalBytes > maxActiveDownloadBytes - size) {
                    sendOverload(exchange);
                    return;
                }
                totalBytes += size;
            } catch (IOException e) {
                WebPanFiles.sendText(exchange, 404, "文件已不存在", false);
                return;
            }
        }

        String token = firstQueryValue(query, "captcha");
        String answer = firstQueryValue(query, "answer");
        boolean answerAccepted = false;
        boolean answerRejected = false;
        boolean authorizedSingleDownload = false;
        boolean capacityRejected = false;
        String failedUser = null;
        Path authorizedFile = null;
        long now = clock.getAsLong();
        synchronized (this) {
            CaptchaChallenge challenge = token == null ? null : session.captchaChallenges.get(token);
            if (challenge != null && now >= challenge.expiresAt) {
                session.captchaChallenges.remove(token);
                challenge = null;
            }
            if (challenge != null && !challenge.passed && !challenge.targetFiles.equals(fileKeys)) {
                challenge = null;
            }
            // An answer may only complete the exact batch for which the challenge was issued.
            // A passed challenge may still authorize its individual files below, but it must
            // never be re-bound to a different batch by replaying the answer parameter.
            if (challenge != null && answer != null && !challenge.targetFiles.equals(fileKeys)) {
                challenge = null;
            }
            if (challenge != null && answer != null) {
                if (challenge.answer.equals(answer)) {
                    challenge.passed = true;
                    answerAccepted = true;
                } else {
                    session.captchaChallenges.remove(token);
                    answerRejected = true;
                    failedUser = session.userId;
                }
            } else if (challenge != null && challenge.passed && requested.size() == 1
                    && challenge.targetFiles.containsAll(fileKeys)
                    && !challenge.downloadedFiles.containsAll(fileKeys)) {
                if (!hasDownloadCapacity(totalBytes)) {
                    capacityRejected = true;
                } else {
                    // Consume this file's one-time authorization while holding the short state lock.
                    authorizedSingleDownload = true;
                    authorizedFile = files.get(0);
                    challenge.downloadedFiles.add(fileKeys.iterator().next());
                    if (challenge.downloadedFiles.containsAll(challenge.targetFiles)) {
                        session.captchaChallenges.remove(token);
                    }
                }
            }
        }
        if (answerAccepted) {
            boolean answerCapacityRejected = false;
            synchronized (this) {
                if (!hasDownloadCapacity(totalBytes)) {
                    session.captchaChallenges.remove(token);
                    answerCapacityRejected = true;
                }
            }
            if (answerCapacityRejected) {
                sendOverload(exchange);
                return;
            }
            WebPanFiles.sendHtml(exchange, 200, batchDownloadPage(token, requested), false);
            return;
        }
        if (capacityRejected) {
            sendOverload(exchange);
            return;
        }
        if (answerRejected) {
            WebPanFiles.sendHtml(exchange, 403,
                    "<main class=\"wrap\"><h1>验证答案错误，已断开所有连接</h1></main>", false);
            failCaptcha(session, failedUser);
            return;
        }
        if (authorizedSingleDownload) {
            WebPanFiles.serveDownload(exchange, authorizedFile, authorizedFile.getFileName().toString(),
                    Files.size(authorizedFile), false, (request, selected) -> tryAcquireDownload(selected));
            return;
        }

        List<Path> images = findCaptchaImages(captchaDirectory);
        if (images.isEmpty()) {
            WebPanFiles.sendHtml(exchange, 503,
                    "<main class=\"wrap\"><h1>验证图片不可用，下载已拒绝</h1></main>", false);
            return;
        }
        Path image = images.get(RANDOM.nextInt(images.size()));
        String imageName = image.getFileName().toString();
        String expected = imageName.substring(0, imageName.length() - 4);
        String newToken = newCaptchaToken();
        synchronized (this) {
            session.captchaChallenges.clear();
            session.captchaChallenges.put(newToken, new CaptchaChallenge(newToken, image, expected, fileKeys,
                    now + CAPTCHA_LIFETIME_MS));
        }
        WebPanFiles.sendHtml(exchange, 200, batchCaptchaPage(newToken, requested), false);
    }

    private static String firstQueryValue(Map<String, List<String>> query, String key) {
        List<String> values = query.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private boolean hasDownloadCapacity(long bytes) {
        if (bytes < 0 || bytes > maxActiveDownloadBytes) return false;
        long current = activeDownloadBytes.get();
        return current <= maxActiveDownloadBytes - bytes;
    }

    private static void sendOverload(WebPanExchange exchange) throws IOException {
        WebPanFiles.sendHtml(exchange, 503, overloadPage(), false);
    }

    private static String overloadPage() {
        return WebPanFiles.htmlPage("服务器过载", "<main class=\"wrap\"><h1>服务器过载，请稍后重试</h1></main>");
    }

    private String batchDownloadPage(String token, List<String> files) {
        StringBuilder body = new StringBuilder("<main class=\"wrap\"><h1>验证通过</h1>")
                .append("<p>所选文件已通过验证，下载即将开始。</p><div class=\"actions\">");
        for (String file : files) {
            String location = "/webpan/_batch?captcha=" + WebPanFiles.encodeQueryValue(token)
                    + "&file=" + WebPanFiles.encodeQueryValue(file);
            body.append("<a class=\"btn\" data-batch-download=\"1\" download style=\"display:none\" href=\"")
                    .append(WebPanFiles.esc(location))
                    .append("\">下载 ").append(WebPanFiles.esc(file.substring(file.lastIndexOf('/') + 1))).append("</a>");
        }
        body.append("</div><p class=\"meta\">浏览器可能会分别询问多个文件的下载权限。</p></main>");
        return WebPanFiles.htmlPage("验证通过", body.toString());
    }

    private String batchCaptchaPage(String token, List<String> files) {
        StringBuilder body = new StringBuilder("<main class=\"wrap\"><h1>下载验证</h1>")
                .append("<p>请输入下方图片的文件名（不含 .png，区分大小写）。验证通过后将下载所选文件。</p>")
                .append("<form method=\"get\" action=\"/webpan/_batch\">")
                .append("<p><img src=\"/webpan/_captcha/image?captcha=")
                .append(WebPanFiles.esc(token)).append("\" alt=\"验证图片\" style=\"max-width:100%;max-height:260px\"></p>")
                .append("<label>答案 <input name=\"answer\" autocomplete=\"off\" required autofocus></label>")
                .append("<input type=\"hidden\" name=\"captcha\" value=\"").append(WebPanFiles.esc(token)).append("\">");
        for (String file : files) {
            body.append("<input type=\"hidden\" name=\"file\" value=\"")
                    .append(WebPanFiles.esc(file)).append("\">");
        }
        return WebPanFiles.htmlPage("下载验证", body.append("<button class=\"btn\" type=\"submit\">验证并下载</button></form></main>").toString());
    }
}
