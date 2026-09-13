package feixue.chat.server.com;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

// 网页前端功能：TCP 连接分流、HTTP/HTTPS 响应、WebSocket 握手与升级、SSL 监听、网页会话超时
class HttpFrontend {
    private static final String FPS_MUSIC_CONFIG = "games/fps/fpsnmusic.json";
    private static final Pattern FPS_MUSIC_PATH_PATTERN = Pattern.compile(
            "\\\"backgroundMusicPath\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\])*)\\\"");
    private final ChatServer server;
    // CF-Connecting-IP is only trusted when the TCP peer is an official Cloudflare proxy.
    private static final Cidr[] CLOUDFLARE_PROXY_RANGES = new Cidr[] {
            cidr("173.245.48.0", 20), cidr("103.21.244.0", 22), cidr("103.22.200.0", 22),
            cidr("103.31.4.0", 22), cidr("141.101.64.0", 18), cidr("108.162.192.0", 18),
            cidr("190.93.240.0", 20), cidr("188.114.96.0", 20), cidr("197.234.240.0", 22),
            cidr("198.41.128.0", 17), cidr("162.158.0.0", 15), cidr("104.16.0.0", 13),
            cidr("104.24.0.0", 14), cidr("172.64.0.0", 13), cidr("131.0.72.0", 22),
            cidr("2400:cb00::", 32), cidr("2606:4700::", 32), cidr("2803:f800::", 32),
            cidr("2405:b500::", 32), cidr("2405:8100::", 32), cidr("2a06:98c0::", 29),
            cidr("2c0f:f248::", 32)
    };

    private static final class Cidr {
        final byte[] address;
        final int prefixLength;

        Cidr(byte[] address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        boolean contains(InetAddress candidate) {
            byte[] value = candidate.getAddress();
            if (value.length != address.length) return false;
            int wholeBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < wholeBytes; i++) if (value[i] != address[i]) return false;
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (value[wholeBytes] & mask) == (address[wholeBytes] & mask);
        }
    }

    private static Cidr cidr(String address, int prefixLength) {
        try {
            return new Cidr(InetAddress.getByName(address).getAddress(), prefixLength);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    HttpFrontend(ChatServer server) {
        this.server = server;
    }

    static String resolveClientIp(InetAddress peer, String cfConnectingIp) {
        String directIp = peer == null ? "" : peer.getHostAddress();
        if (!isCloudflareProxy(peer) || cfConnectingIp == null) return directIp;
        String candidate = cfConnectingIp.trim();
        if (candidate.length() > 45 || candidate.isEmpty()) return directIp;
        boolean ipv4 = candidate.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        boolean ipv6 = candidate.indexOf(':') >= 0 && candidate.matches("[0-9A-Fa-f:.]+") ;
        if (!ipv4 && !ipv6) return directIp;
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            if ((ipv4 && parsed.getAddress().length != 4) || (ipv6 && parsed.getAddress().length != 16)) return directIp;
            return parsed.getHostAddress();
        } catch (IOException ignored) {
            return directIp;
        }
    }

    private static boolean isCloudflareProxy(InetAddress peer) {
        if (peer == null) return false;
        for (Cidr range : CLOUDFLARE_PROXY_RANGES) if (range.contains(peer)) return true;
        return false;
    }

    SSLServerSocket createSSLServerSocket(int port) throws Exception {
        Path certificatePath = server.config.resolveConfigPath(server.sslCertificateFile);
        Path privateKeyPath = server.config.resolveConfigPath(server.sslPrivateKeyFile);
        if (!Files.exists(certificatePath) || !Files.exists(privateKeyPath)) {
            throw new FileNotFoundException("证书或私钥不存在: " + certificatePath + ", " + privateKeyPath);
        }
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(certificatePath)) {
            certificate = (X509Certificate) certificateFactory.generateCertificate(input);
        }
        PrivateKey privateKey = readPrivateKey(privateKeyPath);
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("cloudflare-origin", privateKey, new char[0],
                new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        server.sslContext = context;
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
        socket.setNeedClientAuth(false);
        socket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        return socket;
    }

    PrivateKey readPrivateKey(Path path) throws Exception {
        String pem = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    void routeIncomingConnection(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            input.mark(8);
            byte[] prefix = new byte[4];
            int prefixLength = 0;
            while (prefixLength < prefix.length) {
                int count = input.read(prefix, prefixLength, prefix.length - prefixLength);
                if (count < 0) {
                    socket.close();
                    return;
                }
                prefixLength += count;
            }
            input.reset();
            String verb = new String(prefix, StandardCharsets.US_ASCII);
            if ("GET ".equals(verb) || "HEAD".equals(verb) || "POST".equals(verb)
                    || "PUT ".equals(verb) || "DELE".equals(verb) || "OPTI".equals(verb)) {
                handleHttpConnection(socket, input);
                return;
            }
            socket.setSoTimeout(0);
            new ClientHandler(server, socket, new RawLineTransport(socket, input), false);
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
        } catch (IOException e) {
            closeQuietly(socket);
            if (server.isRunning && !isExpectedTlsHandshakeDisconnect(e)) {
                server.log("连接分流失败: " + e.getMessage());
            }
        }
    }

    // 浏览器预连接、端口探测或代理取消 TLS 握手时，JSSE 会抛出该异常；连接并未进入聊天协议。
    boolean isExpectedTlsHandshakeDisconnect(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            String detail = (message == null ? "" : message) + " " + cause.toString();
            if (detail.toLowerCase(Locale.ROOT).contains("remote host terminated the handshake")) {
                return true;
            }
        }
        return false;
    }

    void handleHttpConnection(Socket socket, BufferedInputStream input) throws IOException {
        String requestLine = readHttpLine(input);
        if (requestLine == null) {
            socket.close();
            return;
        }
        String[] requestParts = requestLine.split(" ", 3);
        if (requestParts.length != 3) {
            sendHttpResponse(socket, "405 Method Not Allowed", "text/plain; charset=utf-8",
                    "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; count < 100; count++) {
            String line = readHttpLine(input);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        if (!server.webAccessEnabled) {
            sendHttpResponse(socket, "503 Service Unavailable", "text/html; charset=utf-8",
                    ("<!doctype html><meta charset=\"utf-8\"><title>网页端已关闭</title>"
                            + "<body style=\"font-family:sans-serif;padding:40px\"><h1>网页端已关闭</h1>"
                            + "<p>请联系服务器管理员在服务器的“网页端”页面中启动。</p></body>")
                            .getBytes(StandardCharsets.UTF_8), "HEAD".equals(requestParts[0]));
            return;
        }

        String target = requestParts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        boolean wantsWebSocket = "websocket".equalsIgnoreCase(headers.get("upgrade"))
                && headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade");
        if (!(socket instanceof SSLSocket)) {
            String host = headers.getOrDefault("host", "fangfang.dpdns.org");
            String redirect = "https://" + host.split(":", 2)[0] + ":" + server.sslPort + target;
            sendHttpRedirect(socket, redirect);
            return;
        }
        if ("/webpan".equals(path) || path.startsWith("/webpan/")) {
            server.webPan.handle(socket, requestParts[0], target, headers,
                    resolveClientIp(socket.getInetAddress(), headers.get("cf-connecting-ip")));
            return;
        }
        if (!"GET".equals(requestParts[0])) {
            sendHttpResponse(socket, "405 Method Not Allowed", "text/plain; charset=utf-8",
                    "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/ws".equals(path) && wantsWebSocket) {
            if (!isAllowedWebSocketOrigin(headers)) {
                sendHttpResponse(socket, "403 Forbidden", "text/plain; charset=utf-8",
                        "Forbidden origin".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String key = headers.get("sec-websocket-key");
            if (key == null || key.trim().isEmpty() || !"13".equals(headers.get("sec-websocket-version"))) {
                sendHttpResponse(socket, "400 Bad Request", "text/plain; charset=utf-8",
                        "Invalid WebSocket handshake".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String accept = createWebSocketAccept(key.trim());
            WebPanService.Session webSession = server.webPan.newSession(headers.get("cookie"));
            OutputStream output = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "Set-Cookie: " + webSession.cookie() + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            socket.setSoTimeout(0);
            new ClientHandler(server, socket, new WebSocketTransport(socket, input, output), true, webSession,
                    resolveClientIp(socket.getInetAddress(), headers.get("cf-connecting-ip")));
            return;
        }

        if ("/favicon.ico".equals(path)) {
            sendHttpResponse(socket, "204 No Content", "image/x-icon", new byte[0]);
            return;
        }
        if ("/music/game4.mp3".equals(path)) {
            streamGame4Music(socket, headers.get("range"));
            return;
        }
        if (path.startsWith("/vendor/")) {
            String name = path.substring("/vendor/".length());
            if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || name.indexOf("..") >= 0) {
                sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] data = loadVendorResource(name);
            if (data == null) {
                sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String contentType = name.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : name.endsWith(".json") ? "application/json; charset=utf-8"
                    : "application/octet-stream";
            sendHttpResponse(socket, "200 OK", contentType, data);
            return;
        }
        if (path.equals("/frontend-base.js") || path.equals("/frontend-events.js") || path.startsWith("/games/")) {
            String resourcePath = path.substring(1);
            if (!isAllowedFrontendResourcePath(resourcePath)) {
                sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] data = loadFrontendResource(resourcePath);
            if (data == null) {
                sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendHttpResponse(socket, "200 OK", frontendContentType(resourcePath), data);
            return;
        }
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                    "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendHttpResponse(socket, "200 OK", "text/html; charset=utf-8", loadWebClientPage());
    }

    byte[] loadVendorResource(String name) {
        try (InputStream resource = ChatServer.class.getResourceAsStream("/vendor/" + name)) {
            if (resource != null) {
                return readAllBytes(resource, 8 * 1024 * 1024);
            }
        } catch (IOException e) {
            server.log("读取前端资源失败: " + name + " - " + e.getMessage());
        }
        Path vendorPath = server.config.resolveConfigPath("vendor" + File.separator + name);
        try {
            if (Files.exists(vendorPath)) {
                return Files.readAllBytes(vendorPath);
            }
        } catch (IOException e) {
            server.log("读取前端资源失败: " + vendorPath + " - " + e.getMessage());
        }
        return null;
    }

    boolean isAllowedFrontendResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty() || resourcePath.length() > 240
                || resourcePath.indexOf('\\') >= 0 || resourcePath.contains("..")) {
            return false;
        }
        String[] parts = resourcePath.split("/");
        for (String part : parts) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9._-]+")) return false;
        }
        return true;
    }

    String frontendContentType(String resourcePath) {
        if (resourcePath.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (resourcePath.endsWith(".html")) return "text/html; charset=utf-8";
        if (resourcePath.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }

    byte[] loadFrontendResource(String resourcePath) {
        try (InputStream resource = ChatServer.class.getResourceAsStream("/" + resourcePath)) {
            if (resource != null) return readAllBytes(resource, 4 * 1024 * 1024);
        } catch (IOException e) {
            server.log("读取前端资源失败: " + resourcePath + " - " + e.getMessage());
        }
        Path filePath = server.config.resolveConfigPath(resourcePath.replace('/', File.separatorChar));
        try {
            if (Files.isRegularFile(filePath)) return Files.readAllBytes(filePath);
        } catch (IOException e) {
            server.log("读取前端资源失败: " + filePath + " - " + e.getMessage());
        }
        return null;
    }

    // 游戏音乐较大，浏览器会按需请求不同字节段；这里直接流式转发，避免为每个玩家复制整段文件到内存。
    void streamGame4Music(Socket socket, String rangeHeader) throws IOException {
        GameMusicSource source = findGame4Music();
        if (source == null) {
            sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                    "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        final long total = source.length;
        final boolean partial = rangeHeader != null && !rangeHeader.trim().isEmpty();
        ByteRange range = partial ? parseSingleByteRange(rangeHeader, total)
                : new ByteRange(0, Math.max(0, total - 1));
        if (range == null) {
            sendGame4MusicRangeNotSatisfiable(socket, total);
            return;
        }
        long length = total == 0 ? 0 : range.end - range.start + 1;

        try (InputStream input = source.openStream()) {
            skipFully(input, range.start);
            OutputStream output = socket.getOutputStream();
            writeGame4MusicHeaders(output, partial ? "206 Partial Content" : "200 OK", length,
                    partial ? range : null, total);
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new IOException("游戏背景音乐读取提前结束");
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            output.flush();
        } finally {
            closeQuietly(socket);
        }
    }

    GameMusicSource findGame4Music() {
        String configuredPath = readFpsMusicPath();
        if (configuredPath == null || configuredPath.isEmpty()) {
            server.log("读取 FPS 背景音乐失败: " + FPS_MUSIC_CONFIG + " 未配置 backgroundMusicPath");
            return null;
        }
        try {
            Path musicPath = Paths.get(configuredPath);
            if (!musicPath.isAbsolute()) {
                musicPath = Paths.get(System.getProperty("user.dir")).resolve(musicPath);
            }
            musicPath = musicPath.toAbsolutePath().normalize();
            if (Files.isRegularFile(musicPath)) {
                return new GameMusicSource(null, musicPath, Files.size(musicPath));
            }
            server.log("读取 FPS 背景音乐失败: 文件不存在 " + musicPath);
        } catch (IOException e) {
            server.log("读取 FPS 背景音乐失败: " + configuredPath + " - " + e.getMessage());
        } catch (RuntimeException e) {
            server.log("读取 FPS 背景音乐失败: 路径无效 " + configuredPath);
        }
        return null;
    }

    String readFpsMusicPath() {
        String content = null;
        Path configPath = server.config.resolveConfigPath(FPS_MUSIC_CONFIG.replace('/', File.separatorChar));
        try {
            if (Files.isRegularFile(configPath)) {
                content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            server.log("读取 FPS 音乐配置失败: " + configPath + " - " + e.getMessage());
        }
        if (content == null) {
            byte[] resource = loadFrontendResource(FPS_MUSIC_CONFIG);
            if (resource != null) content = new String(resource, StandardCharsets.UTF_8);
        }
        if (content == null) return null;
        Matcher matcher = FPS_MUSIC_PATH_PATTERN.matcher(content);
        return matcher.find() ? server.config.unescapeJsonString(matcher.group(1)).trim() : null;
    }

    ByteRange parseSingleByteRange(String header, long total) {
        String value = header == null ? "" : header.trim();
        if (total <= 0 || !value.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = value.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-')) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        if (startText.isEmpty()) {
            long suffixLength = parseNonNegativeLong(endText);
            if (suffixLength <= 0) {
                return null;
            }
            return new ByteRange(Math.max(0, total - suffixLength), total - 1);
        }

        long start = parseNonNegativeLong(startText);
        if (start < 0 || start >= total) {
            return null;
        }
        long end = endText.isEmpty() ? total - 1 : parseNonNegativeLong(endText);
        if (end < start) {
            return null;
        }
        return new ByteRange(start, Math.min(end, total - 1));
    }

    long parseNonNegativeLong(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("游戏背景音乐读取提前结束");
            }
            remaining--;
        }
    }

    void sendGame4MusicRangeNotSatisfiable(Socket socket, long total) throws IOException {
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                + "Content-Range: bytes */" + total + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Content-Length: 0\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        closeQuietly(socket);
    }

    void writeGame4MusicHeaders(OutputStream output, String status, long length, ByteRange range, long total)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: audio/mpeg\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (range == null ? "" : "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\n")
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Permissions-Policy: microphone=(self)\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' blob:; worker-src blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self' ws: wss:\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
    }

    static final class ByteRange {
        final long start;
        final long end;

        ByteRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class GameMusicSource {
        final URL resourceUrl;
        final Path filePath;
        final long length;

        GameMusicSource(URL resourceUrl, Path filePath, long length) {
            this.resourceUrl = resourceUrl;
            this.filePath = filePath;
            this.length = length;
        }

        InputStream openStream() throws IOException {
            return resourceUrl != null ? resourceUrl.openStream() : Files.newInputStream(filePath);
        }
    }

    boolean isAllowedWebSocketOrigin(Map<String, String> headers) {
        String origin = headers.get("origin");
        String host = headers.get("host");
        if (origin == null) {
            return true;
        }
        if (host == null) {
            return false;
        }
        return origin.equalsIgnoreCase("http://" + host) || origin.equalsIgnoreCase("https://" + host);
    }

    String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCarriageReturn = false;
        while (line.size() <= ChatServer.MAX_HTTP_LINE_BYTES) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (sawCarriageReturn) {
                if (value == '\n') {
                    return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                line.write('\r');
                sawCarriageReturn = false;
            }
            if (value == '\r') {
                sawCarriageReturn = true;
            } else if (value == '\n') {
                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            } else {
                line.write(value);
            }
        }
        throw new IOException("HTTP header line is too long");
    }

    String createWebSocketAccept(String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] value = digest.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(value);
        } catch (Exception e) {
            throw new IOException("WebSocket handshake failed", e);
        }
    }

    byte[] loadWebClientPage() throws IOException {
        byte[] page = null;
        try (InputStream resource = ChatServer.class.getResourceAsStream(ChatServer.WEB_CLIENT_RESOURCE)) {
            if (resource != null) {
                page = readAllBytes(resource, 4 * 1024 * 1024);
            }
        }
        if (page == null) {
            Path pagePath = server.config.resolveConfigPath("web-client.html");
            if (Files.exists(pagePath)) page = Files.readAllBytes(pagePath);
        }
        if (page == null) {
            return ("<!doctype html><meta charset=\"utf-8\"><title>网页端资源缺失</title>"
                    + "<h1>网页端资源缺失</h1>").getBytes(StandardCharsets.UTF_8);
        }
        String html = new String(page, StandardCharsets.UTF_8);
        String[] gamePages = { "games/snake/index.html", "games/red/index.html", "games/earth/index.html", "games/fps/index.html" };
        for (String gamePage : gamePages) {
            String marker = "<!-- GAME_INCLUDE: " + gamePage + " -->";
            byte[] fragment = loadFrontendResource(gamePage);
            String replacement = fragment == null
                    ? "<section class=\"screen entry-screen\"><p>游戏资源缺失：" + gamePage + "</p></section>"
                    : new String(fragment, StandardCharsets.UTF_8);
            html = html.replace(marker, replacement);
        }
        return html.getBytes(StandardCharsets.UTF_8);
    }

    byte[] readAllBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) {
                throw new IOException("Resource is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    void sendHttpResponse(Socket socket, String status, String contentType, byte[] body) throws IOException {
        sendHttpResponse(socket, status, contentType, body, false);
    }

    void sendHttpResponse(Socket socket, String status, String contentType, byte[] body, boolean head) throws IOException {
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Permissions-Policy: microphone=(self)\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' blob:; worker-src blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self' ws: wss:\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        if (!head) output.write(body);
        output.flush();
        socket.close();
    }

    void sendHttpRedirect(Socket socket, String location) throws IOException {
        String response = "HTTP/1.1 308 Permanent Redirect\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        socket.close();
    }

    void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void startWebIdleTimeoutTask() {
        final ServerSocket activeServerSocket = server.serverSocket;
        Thread timeoutThread = new Thread(() -> {
            while (server.isRunning && server.serverSocket == activeServerSocket
                    && activeServerSocket != null && !activeServerSocket.isClosed()) {
                try {
                    Thread.sleep(30000);
                    long now = System.currentTimeMillis();
                    for (ClientHandler client : new ArrayList<>(server.webClientHandlers)) {
                        if (now - client.lastWebUserActivity > ChatServer.WEB_IDLE_TIMEOUT) {
                            client.sendMessage("/web_idle_timeout|已超过一小时无操作，连接已断开");
                            client.closeConnection();
                            server.log("网页用户 " + client.nickname + " 一小时无操作，已断开");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (server.isRunning) {
                        server.log("网页会话超时检查失败: " + e.getMessage());
                    }
                }
            }
        }, "WebIdleTimeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();
    }

    String encodeWebValue(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    String decodeWebValue(String value) throws IOException {
        if (value == null || value.length() > 1024) {
            throw new IOException("Invalid encoded value");
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid encoded value", e);
        }
    }

    void sendWebChannelList(ClientHandler client) {
        client.sendMessage("/web_channel|" + encodeWebValue("公开频道") + "|"
                + encodeWebValue(ChatServer.PUBLIC_CHANNEL_GROUP) + "|0");
        for (Map.Entry<String, String> entry : server.accountPasswords.entrySet()) {
            String group = server.accountGroups.get(entry.getKey());
            if (group != null) {
                client.sendMessage("/web_channel|" + encodeWebValue(entry.getKey()) + "|"
                        + encodeWebValue(group) + "|" + (entry.getValue().isEmpty() ? "0" : "1"));
            }
        }
        client.sendMessage("/web_channels_end");
    }

    boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    void startSslListenerIfConfigured() {
        if (!server.sslEnabled) {
            server.log("HTTPS/WSS 未启用（ssl-config.json enabled=false）");
            return;
        }
        try {
            server.sslServerSocket = createSSLServerSocket(server.sslPort);
            server.log("HTTPS/WSS 已启动，监听端口: " + server.sslPort);
            Thread listener = new Thread(() -> {
                while (server.isRunning && server.sslServerSocket != null && !server.sslServerSocket.isClosed()) {
                    try {
                        Socket clientSocket = server.sslServerSocket.accept();
                        Thread routerThread = new Thread(() -> routeIncomingConnection(clientSocket),
                                "SSLConnectionRouter-" + clientSocket.getRemoteSocketAddress());
                        routerThread.setDaemon(true);
                        routerThread.start();
                    } catch (IOException e) {
                        if (server.isRunning) server.log("HTTPS/WSS 接收连接失败: " + e.getMessage());
                        break;
                    }
                }
            }, "SSLListener-" + server.sslPort);
            listener.setDaemon(true);
            listener.start();
        } catch (Exception e) {
            server.sslEnabled = false;
            server.log("HTTPS/WSS 启动失败，普通客户端仍可使用: " + e.getMessage());
        }
    }
}
