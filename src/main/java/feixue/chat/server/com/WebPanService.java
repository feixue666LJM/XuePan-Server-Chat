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
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;

final class WebPanService {
    private final Path configPath;
    private Path root = Paths.get(WebPanFiles.DEFAULT_ROOT).toAbsolutePath().normalize();
    private boolean enabled;
    private boolean webEnabled;
    private static final String COOKIE_NAME = "__Host-feixue-web";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Set<Session> sessions = new HashSet<>();
    private final Map<Socket, Session> transfers = new HashMap<>();

    static final class Session {
        final String token;
        boolean revoked;
        BooleanSupplier verified = () -> false;
        Session(String token) { this.token = token; }
        String cookie() { return COOKIE_NAME + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict"; }
    }

    synchronized Session newSession(String cookie) {
        Session existing = findSession(cookie);
        if (existing != null) return new Session(existing.token);
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
        this.configPath = configPath;
        if (Files.isRegularFile(configPath)) {
            Properties settings = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) { settings.load(in); }
            root = Paths.get(settings.getProperty("root", root.toString())).toAbsolutePath().normalize();
            enabled = Boolean.parseBoolean(settings.getProperty("enabled", "false"));
        }
    }

    synchronized Path getRoot() { return root; }
    synchronized boolean isEnabled() { return enabled; }
    synchronized boolean isAvailable() { return enabled && webEnabled && Files.isDirectory(root); }

    synchronized void configure(String directory, boolean allow) throws IOException {
        if (directory.trim().isEmpty()) throw new IOException("请选择共享目录");
        Path next = Paths.get(directory.trim()).toAbsolutePath().normalize();
        if (allow && (!Files.isDirectory(next) || !Files.isReadable(next))) {
            throw new IOException("共享目录不存在或无法读取：" + next);
        }
        Properties settings = new Properties();
        settings.setProperty("root", next.toString());
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
        enabled = allow;
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
        WebPanExchange exchange;
        try {
            URI uri = URI.create(target);
            String path = uri.getRawPath();
            if (!"/webpan".equals(path) && !path.startsWith("/webpan/")) throw new IllegalArgumentException();
            String relative = path.substring("/webpan".length());
            if (relative.isEmpty()) relative = "/";
            if (uri.getRawQuery() != null) relative += "?" + uri.getRawQuery();
            exchange = new WebPanExchange(socket, method, URI.create("http://localhost" + relative), headers);
        } catch (IllegalArgumentException e) {
            exchange = new WebPanExchange(socket, method, URI.create("/"), headers);
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
        try { WebPanFiles.handle(exchange, publishedRoot, null); }
        finally {
            exchange.close();
            synchronized (this) { transfers.remove(socket); }
        }
    }
}
