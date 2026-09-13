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

final class WebPanService {
    static final int MAX_ACTIVE_DOWNLOADS = 50;
    static final long DEFAULT_DOWNLOAD_INTERVAL_MS = 30_000L;
    static final long BURST_DOWNLOAD_INTERVAL_MS = 5 * 60_000L;
    static final long BURST_PENALTY_DURATION_MS = 60 * 60_000L;
    static final int RAPID_REQUEST_THRESHOLD = 10;
    static final long CAPTCHA_LIFETIME_MS = 5 * 60_000L;
    private final Path configPath;
    private final LongSupplier clock;
    private final Semaphore downloadSlots;
    private final AtomicInteger activeDownloads = new AtomicInteger();
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
        final String targetFile;
        final long expiresAt;
        boolean passed;

        CaptchaChallenge(String token, Path image, String answer, String targetFile, long expiresAt) {
            this.token = token;
            this.image = image;
            this.answer = answer;
            this.targetFile = targetFile;
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
        private final AtomicBoolean released = new AtomicBoolean();

        private DownloadAdmission(WebPanService service) {
            this.service = service;
            this.retryAfterSeconds = 0;
            this.message = "";
            this.rejectionStatus = 0;
            this.html = false;
            this.afterResponse = null;
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
        }

        boolean isAllowed() { return service != null; }
        int getRetryAfterSeconds() { return retryAfterSeconds; }
        String getMessage() { return message; }
        int getRejectionStatus() { return rejectionStatus; }
        boolean isHtml() { return html; }
        void afterResponse() { if (afterResponse != null) afterResponse.run(); }

        @Override public void close() {
            if (service != null && released.compareAndSet(false, true)) {
                service.releaseDownloadSlot();
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
        this.downloadSlots = new Semaphore(maxActiveDownloads, true);
        if (Files.isRegularFile(configPath)) {
            Properties settings = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) { settings.load(in); }
            root = Paths.get(settings.getProperty("root", root.toString())).toAbsolutePath().normalize();
            String captchaPath = settings.getProperty("captchaDirectory", "").trim();
            if (!captchaPath.isEmpty()) captchaDirectory = Paths.get(captchaPath).toAbsolutePath().normalize();
            enabled = Boolean.parseBoolean(settings.getProperty("enabled", "false"));
        }
    }

    synchronized Path getRoot() { return root; }
    synchronized Path getCaptchaDirectory() { return captchaDirectory; }
    synchronized boolean isEnabled() { return enabled; }
    synchronized boolean isAvailable() {
        return enabled && webEnabled && Files.isDirectory(root) && isCaptchaAvailable();
    }
    synchronized boolean isCaptchaAvailable() { return findCaptchaImages(captchaDirectory).size() > 0; }
    int getActiveDownloadCount() { return activeDownloads.get(); }

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
        activeDownloads.incrementAndGet();
        return new DownloadAdmission(this);
    }

    private void releaseDownloadSlot() {
        activeDownloads.decrementAndGet();
        downloadSlots.release();
    }

    synchronized void configure(String directory, boolean allow) throws IOException {
        configure(directory, captchaDirectory == null ? "" : captchaDirectory.toString(), allow);
    }

    synchronized void configure(String directory, String captchaFolder, boolean allow) throws IOException {
        if (directory.trim().isEmpty()) throw new IOException("请选择共享目录");
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
        if (challenge != null && !fileKey.equals(challenge.targetFile)) challenge = null;
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
        session.captchaChallenges.put(newToken, new CaptchaChallenge(newToken, image, expected, fileKey,
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
            } else {
                WebPanFiles.handle(exchange, publishedRoot,
                        (request, file) -> requireCaptcha(authorized, (WebPanExchange) request, file));
            }
        }
        finally {
            exchange.close();
            synchronized (this) { transfers.remove(socket); }
        }
    }
}
