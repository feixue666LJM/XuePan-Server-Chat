/*
 * ============================================================================
 *  web.java —— 单文件 Java 网盘 / 文件服务（零第三方依赖，仅用 JDK 自带 API）
 * ----------------------------------------------------------------------------
 *  功能：
 *    · 把本地某个文件夹（默认 D:\webpan）通过 HTTP 发布到局域网 / 公网
 *    · 绑定 0.0.0.0，默认端口 5050
 *    · 浏览器打开即进入目录列表，可一层层点进子目录
 *    · 点击 .txt / .md / .log / .json 等文本文件 —— 直接在浏览器里看内容
 *    · 点击其它文件（zip / exe / docx / 图片 / 视频 ...） —— 直接下载
 *    · 支持中文文件名、大文件、断点续传（HTTP Range）、HEAD 请求
 *
 *  编译 & 打包（JDK 9+）：
 *      javac -encoding UTF-8 -d build web.java
 *      jar --create --file webpan.jar --main-class web -C build .
 *  运行：
 *      java -jar webpan.jar
 *      java -jar webpan.jar --root D:\webpan --port 5050
 *      java -jar webpan.jar --root D:\webpan --port 5050 --user admin --pass 123456
 * ============================================================================
 */

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class web {

    /* ======================= 默认配置 ======================= */

    static final int    DEFAULT_PORT = 5050;             // 默认端口
    static final String DEFAULT_ROOT = "D:\\webpan";     // 默认发布目录
    static final long   PREVIEW_MAX  = 2L * 1024 * 1024; // 超过 2MB 的文本不再预览，改为下载
    static final int    BUF_SIZE     = 64 * 1024;        // 传输缓冲区

    /** 这些扩展名“点击直接看内容”，其余一律下载 */
    static final Set<String> PREVIEW_EXT = new HashSet<>(Arrays.asList(
            "txt", "text", "log", "md", "markdown", "json", "xml", "yml", "yaml",
            "ini", "cfg", "conf", "properties", "csv", "tsv", "sql",
            "java", "js", "mjs", "ts", "css", "py", "c", "h", "cpp", "hpp", "cs",
            "go", "rs", "php", "rb", "sh", "bat", "cmd", "ps1",
            "htm", "html", "xhtml", "svg", "gitignore", "license"));

    static final String CSS =
            "*{box-sizing:border-box}"
          + "body{margin:0;font:14px/1.6 -apple-system,'Segoe UI','Microsoft YaHei',Arial,sans-serif;"
          +      "background:#f5f6f8;color:#1f2430}"
          + "header.bar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;"
          +      "padding:14px 18px;background:#2b3445;color:#fff}"
          + "header.bar h1{margin:0;font-size:16px;font-weight:600;word-break:break-all}"
          + ".actions{display:flex;gap:8px;flex-wrap:wrap}"
          + ".btn{display:inline-block;padding:6px 12px;border-radius:6px;background:#4a90e2;color:#fff;"
          +      "text-decoration:none;font-size:13px;white-space:nowrap}"
          + ".btn:hover{background:#3a7bc8}"
          + ".wrap{max-width:1080px;margin:0 auto;padding:16px}"
          + "nav.crumb{padding:10px 14px;background:#fff;border-radius:8px;margin-bottom:12px;"
          +      "word-break:break-all;box-shadow:0 1px 2px rgba(0,0,0,.06)}"
          + "nav.crumb a{color:#2b6cb0;text-decoration:none}"
          + "nav.crumb a:hover{text-decoration:underline}"
          + "nav.crumb span{color:#aab}"
          + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;"
          +      "box-shadow:0 1px 2px rgba(0,0,0,.06)}"
          + "th,td{padding:9px 14px;text-align:left;border-bottom:1px solid #eef0f3}"
          + "th{background:#fafbfc;font-size:12px;color:#6b7280;text-transform:uppercase;letter-spacing:.04em}"
          + "th.r,td.r{text-align:right;white-space:nowrap;color:#6b7280;font-size:13px}"
          + "tr:last-child td{border-bottom:none}"
          + "tr:hover td{background:#f8fafc}"
          + "td.name a{color:#1a56a0;text-decoration:none;word-break:break-all}"
          + "td.name a:hover{text-decoration:underline}"
          + ".dl{color:#9aa3af;text-decoration:none;margin-left:8px;font-size:12px}"
          + ".dl:hover{color:#4a90e2}"
          + "footer{color:#8a93a0;font-size:12px;padding:16px 4px;text-align:center}"
          + ".meta{padding:10px 14px;color:#6b7280;font-size:12px}"
          + "pre.view{margin:0 0 24px;padding:16px;background:#fff;border-radius:8px;overflow:auto;"
          +      "box-shadow:0 1px 2px rgba(0,0,0,.06);font:13px/1.55 'Cascadia Mono',Consolas,'Courier New',monospace;"
          +      "white-space:pre-wrap;word-break:break-word;tab-size:4}"
          + ".empty{padding:24px;text-align:center;color:#8a93a0;background:#fff;border-radius:8px}"
          + "@media(max-width:640px){td.r.hide,th.r.hide{display:none}}";

    /* ======================= 程序入口 ======================= */

    public static void main(String[] args) throws Exception {
        // 让控制台按它自己的编码输出；输出被重定向时用 UTF-8，避免中文日志乱码
        try {
            java.io.Console con = System.console();
            String enc = (con != null && con.charset() != null) ? con.charset().name() : "UTF-8";
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, enc));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, enc));
        } catch (Exception ignore) { }

        int port = DEFAULT_PORT;
        String rootArg = DEFAULT_ROOT;
        String user = null, pass = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            try {
                switch (a) {
                    case "-p": case "--port": port = Integer.parseInt(args[++i]); break;
                    case "-r": case "--root": rootArg = args[++i]; break;
                    case "-u": case "--user": user = args[++i]; break;
                    case "-w": case "--pass": pass = args[++i]; break;
                    case "-h": case "--help": usage(); return;
                    default:
                        if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7));
                        else if (a.startsWith("--root=")) rootArg = a.substring(7);
                        else if (a.startsWith("--user=")) user = a.substring(7);
                        else if (a.startsWith("--pass=")) pass = a.substring(7);
                        else { System.err.println("未知参数: " + a); usage(); return; }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("[错误] 参数 " + a + " 缺少取值"); usage(); return;
            } catch (NumberFormatException e) {
                System.err.println("[错误] 端口必须是数字: " + e.getMessage()); usage(); return;
            }
        }

        if (port < 1 || port > 65535) {
            System.err.println("[错误] 端口超出范围（1-65535）: " + port); return;
        }
        if (user == null && pass != null) {
            System.err.println("[错误] 只写了 --pass 没写 --user，这样不会开启认证。");
            System.err.println("       正确写法: java -jar webpan.jar --user 用户名 --pass 密码");
            return;
        }

        final Path root = Paths.get(rootArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                System.out.println("[提示] 发布目录不存在，已自动创建: " + root);
            } catch (IOException e) {
                System.err.println("[错误] 发布目录不存在且无法创建: " + root + " -> " + e);
                return;
            }
        }

        final String authHeader = (user == null) ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8));

        // 绑定 0.0.0.0：局域网 + 公网（端口映射后）都能访问
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException be) {
            System.err.println("[错误] 端口 " + port + " 已被占用，或当前账户无权绑定该端口。");
            System.err.println("       查看占用: netstat -ano | findstr :" + port);
            System.err.println("       换端口  : java -jar webpan.jar --port 5051");
            return;
        }
        server.createContext("/", ex -> handle(ex, root, authHeader));
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[退出] 正在关闭服务 ...");
            server.stop(0);
        }));

        server.start();
        banner(root, port, user != null);
    }

    static void usage() {
        System.out.println("用法: java -jar webpan.jar [选项]");
        System.out.println("  -p, --port <端口>   监听端口，默认 " + DEFAULT_PORT);
        System.out.println("  -r, --root <目录>   发布目录，默认 " + DEFAULT_ROOT);
        System.out.println("  -u, --user <用户>   开启 Basic 认证的用户名（可选）");
        System.out.println("  -w, --pass <密码>   开启 Basic 认证的密码（可选）");
        System.out.println("  -h, --help          显示帮助");
    }

    static void banner(Path root, int port, boolean auth) {
        System.out.println("============================================================");
        System.out.println("  webpan 文件服务已启动");
        System.out.println("------------------------------------------------------------");
        System.out.println("  发布目录 : " + root);
        System.out.println("  监听端口 : " + port + "  (0.0.0.0，局域网/公网均可访问)");
        System.out.println("  访问认证 : " + (auth ? "已开启 Basic 认证" : "无（任何人可访问，请注意隐私）"));
        System.out.println("  本机访问 : http://127.0.0.1:" + port + "/");
        for (String ip : localIPv4()) {
            System.out.println("  局域网   : http://" + ip + ":" + port + "/");
        }
        System.out.println("  公网访问 : http://<你的公网IP或域名>:" + port + "/");
        System.out.println("            （需在路由器/光猫上把 " + port + " 端口映射到本机，并放行防火墙）");
        System.out.println("------------------------------------------------------------");
        System.out.println("  按 Ctrl+C 停止服务");
        System.out.println("============================================================");
    }

    static List<String> localIPv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress ia = addrs.nextElement();
                    if (ia instanceof java.net.Inet4Address && !ia.isLoopbackAddress()) {
                        String s = ia.getHostAddress();
                        if (!out.contains(s)) out.add(s);
                    }
                }
            }
        } catch (Exception ignore) { }
        return out;
    }

    /* ======================= 请求分发 ======================= */

    static void handle(HttpExchange ex, Path root, String authHeader) {
        long t0 = System.nanoTime();
        int code = 200;
        String client = "-";
        boolean head = false;
        try {
            if (ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null) {
                client = ex.getRemoteAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignore) { }

        try {
            String method = ex.getRequestMethod();
            head = "HEAD".equalsIgnoreCase(method);

            if (!head && !"GET".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                code = 405; sendText(ex, 405, "405 只支持 GET / HEAD", head); return;
            }

            if (authHeader != null) {
                String got = ex.getRequestHeaders().getFirst("Authorization");
                if (!authMatches(got, authHeader)) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"webpan\"");
                    code = 401; sendText(ex, 401, "401 需要登录", head); return;
                }
            }

            URI uri = ex.getRequestURI();
            String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String path = decodeUrlPath(rawPath);

            if ("/favicon.ico".equals(path)) { code = 204; ex.sendResponseHeaders(204, -1); return; }

            Map<String, String> q = parseQuery(uri.getRawQuery());
            Path target = resolve(root, path);

            if (target == null) {
                code = 403; sendText(ex, 403, "403 非法路径", head); return;
            }
            if (Files.isDirectory(target)) {
                String dirUrl = path.endsWith("/") ? path : path + "/";
                listDir(ex, target, dirUrl, root, head, q);
            } else if (Files.isRegularFile(target)) {
                serveFile(ex, target, path, head, q);
            } else {
                code = 404; sendText(ex, 404, "404 未找到: " + path, head);
            }
        } catch (IOException e) {
            // 目录/文件读不了（权限、被占用）或客户端提前断开：尽量回一个明确的状态码
            code = (e instanceof java.nio.file.AccessDeniedException) ? 403
                 : (e instanceof java.nio.file.NoSuchFileException) ? 404 : 500;
            String msg = code == 403 ? "403 没有权限读取该目录或文件"
                       : code == 404 ? "404 文件已不存在"
                       : "500 读取失败: " + e;
            try { sendText(ex, code, msg, head); }
            catch (Exception ignore) { code = 499; }   // 响应已发出或连接已断开
        } catch (Exception e) {
            code = 500;
            // HEAD 与成功响应一样不得发送响应体；保留 Content-Length 便于客户端获知错误正文大小。
            try { sendText(ex, 500, "500 服务器内部错误: " + e, head); } catch (Exception ignore) { }
        } finally {
            try { ex.close(); } catch (Exception ignore) { }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%s  %-15s %s -> %d  (%d ms)%n",
                    new SimpleDateFormat("HH:mm:ss").format(new Date()), client, safeUri(ex), code, ms);
        }
    }

    static String safeUri(HttpExchange ex) {
        try { return ex.getRequestURI().toString(); } catch (Exception e) { return "-"; }
    }

    /** Basic 认证比较：方案名 "Basic" 大小写不敏感，凭据部分严格比较 */
    static boolean authMatches(String got, String want) {
        if (got == null) return false;
        got = got.trim();
        if (got.length() != want.length()) return false;
        return got.regionMatches(true, 0, want, 0, 5)
            && got.regionMatches(false, 5, want, 5, want.length() - 5);
    }

    /* ======================= 路径安全 ======================= */

    /** 目标必须始终落在发布目录内，杜绝 ../../ 与符号链接逃逸 */
    static Path resolve(Path root, String urlPath) {
        String p = urlPath;
        while (p.startsWith("/")) p = p.substring(1);
        if (p.indexOf('\0') >= 0) return null;
        Path candidate = root.resolve(p).normalize();
        if (!candidate.startsWith(root)) return null;
        try {
            if (Files.exists(candidate)) {
                Path realRoot = root.toRealPath();
                Path real = candidate.toRealPath();
                if (!real.startsWith(realRoot)) return null;
                return real;
            }
        } catch (IOException ignore) { }
        return candidate;
    }

    static String decodeUrlPath(String raw) {
        try {
            // URLDecoder 会把 '+' 当空格，路径里的 '+' 需先转义
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> m = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return m;
        for (String kv : rawQuery.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignore) { }
        }
        return m;
    }

    /* ======================= 目录列表 ======================= */

    static void listDir(HttpExchange ex, Path dir, String dirUrl, Path root,
                        boolean head, Map<String, String> q) throws IOException {
        List<Path> items = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) items.add(p);
        }

        final String sort = q.getOrDefault("sort", "name");
        Comparator<Path> cmp;
        if ("size".equals(sort)) {
            cmp = Comparator.comparingLong(web::sizeOf).reversed();
        } else if ("time".equals(sort)) {
            cmp = Comparator.comparingLong(web::mtimeOf).reversed();
        } else {
            cmp = Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        }
        items.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(cmp));

        String title = dirUrl.equals("/") ? "webpan 网盘" : dirUrl;

        StringBuilder b = new StringBuilder();
        b.append("<header class=\"bar\"><h1>📁 ").append(esc(title)).append("</h1>")
         .append("<div class=\"actions\">")
         .append("<a class=\"btn\" href=\"").append(esc(enc(dirUrl))).append("?sort=name\">按名称</a>")
         .append("<a class=\"btn\" href=\"").append(esc(enc(dirUrl))).append("?sort=time\">按时间</a>")
         .append("<a class=\"btn\" href=\"").append(esc(enc(dirUrl))).append("?sort=size\">按大小</a>")
         .append("</div></header><div class=\"wrap\">");

        // 面包屑导航
        b.append("<nav class=\"crumb\"><a href=\"/\">🏠 根目录</a>");
        String acc = "";
        for (String seg : dirUrl.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc + "/" + seg;
            b.append(" <span>/</span> <a href=\"").append(esc(enc(acc + "/"))).append("\">")
             .append(esc(seg)).append("</a>");
        }
        b.append("</nav>");

        b.append("<table><thead><tr><th>名称</th>")
         .append("<th class=\"r\">大小</th><th class=\"r hide\">修改时间</th></tr></thead><tbody>");

        if (!dirUrl.equals("/")) {
            b.append("<tr><td class=\"name\"><a href=\"").append(esc(enc(parentOf(dirUrl)))).append("\">⬆ 上级目录</a></td>")
             .append("<td class=\"r\">-</td><td class=\"r hide\">-</td></tr>");
        }

        int dirs = 0, files = 0;
        for (Path p : items) {
            boolean isDir;
            try { isDir = Files.isDirectory(p); } catch (Exception e) { continue; }
            String name = p.getFileName().toString();
            String ext = extOf(name);
            String childUrl = joinUrl(dirUrl, name);
            String time = fmtTime(mtimeOf(p));
            String icon = isDir ? "📁" : iconOf(ext);

            b.append("<tr><td class=\"name\">")
             .append("<a href=\"").append(esc(enc(isDir ? childUrl + "/" : childUrl))).append("\">")
             .append(icon).append(' ').append(esc(name)).append(isDir ? "/" : "").append("</a>");

            if (!isDir && PREVIEW_EXT.contains(ext)) {
                b.append("<a class=\"dl\" title=\"直接下载\" href=\"")
                 .append(esc(enc(childUrl))).append("?download=1\">⬇ 下载</a>");
            }
            b.append("</td><td class=\"r\">").append(isDir ? "-" : human(sizeOf(p))).append("</td>")
             .append("<td class=\"r hide\">").append(time).append("</td></tr>");

            if (isDir) dirs++; else files++;
        }
        b.append("</tbody></table>");

        if (items.isEmpty()) {
            b.append("<div class=\"empty\" style=\"margin-top:12px\">这个目录是空的，把文件放进来即可。</div>");
        }
        b.append("<footer>共 ").append(dirs).append(" 个文件夹 / ").append(files)
         .append(" 个文件 &nbsp;·&nbsp; 文本文件点击即可看内容，其它文件点击直接下载</footer>");
        b.append("</div>");

        sendHtml(ex, 200, htmlPage(title, b.toString()), head);
    }

    /* ======================= 文件响应 ======================= */

    static void serveFile(HttpExchange ex, Path f, String path, boolean head,
                          Map<String, String> q) throws IOException {
        String name = f.getFileName().toString();
        String ext = extOf(name);
        long len = Files.size(f);
        boolean forceDownload = "1".equals(q.get("download")) || "1".equals(q.get("dl"));
        boolean preview = !forceDownload && PREVIEW_EXT.contains(ext) && len <= PREVIEW_MAX;

        if (preview) servePreview(ex, f, path, name, len, head);
        else serveDownload(ex, f, name, len, head);
    }

    /** 文本预览：转义后放进 <pre>，并用 CSP 防止文件内容里的脚本被执行 */
    static void servePreview(HttpExchange ex, Path f, String path, String name,
                             long len, boolean head) throws IOException {
        byte[] data = Files.readAllBytes(f);
        Decoded d = decodeText(data);

        StringBuilder b = new StringBuilder();
        b.append("<header class=\"bar\"><h1>📄 ").append(esc(name)).append("</h1>")
         .append("<div class=\"actions\">")
         .append("<a class=\"btn\" href=\"").append(esc(enc(path))).append("?download=1\">⬇ 下载</a>")
         .append("<a class=\"btn\" href=\"").append(esc(enc(parentOf(path)))).append("\">↰ 返回</a>")
         .append("</div></header><div class=\"wrap\">")
         .append("<div class=\"meta\">").append(human(len)).append(" &nbsp;·&nbsp; ")
         .append(fmtTime(mtimeOf(f))).append(" &nbsp;·&nbsp; 编码 ").append(esc(d.charset))
         .append("</div>")
         .append("<pre class=\"view\">").append(esc(d.text)).append("</pre>")
         .append("<footer><a href=\"").append(esc(enc(path))).append("?download=1\">⬇ 下载该文件</a></footer>")
         .append("</div>");

        byte[] out = htmlPage(name, b.toString()).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        h.set("Content-Length", String.valueOf(out.length));
        if (head) { ex.sendResponseHeaders(200, -1); return; }
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); os.flush(); }
    }

    /** 下载：application/octet-stream + attachment，支持 Range 断点续传 */
    static void serveDownload(HttpExchange ex, Path f, String name, long len, boolean head) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Disposition", contentDisposition(name));
        h.set("Accept-Ranges", "bytes");
        h.set("X-Content-Type-Options", "nosniff");

        long[] range = parseRange(ex.getRequestHeaders().getFirst("Range"), len);
        if (range != null) {
            long start = range[0], end = range[1], count = end - start + 1;
            h.set("Content-Range", "bytes " + start + "-" + end + "/" + len);
            h.set("Content-Length", String.valueOf(count));
            if (head) { ex.sendResponseHeaders(206, -1); return; }
            ex.sendResponseHeaders(206, count);
            try (InputStream in = Files.newInputStream(f); OutputStream os = ex.getResponseBody()) {
                skipFully(in, start);
                copy(in, os, count);
            }
            return;
        }

        if (len == 0) {
            if (head) { ex.sendResponseHeaders(200, -1); return; }
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) { os.flush(); }
            return;
        }
        h.set("Content-Length", String.valueOf(len));
        if (head) { ex.sendResponseHeaders(200, -1); return; }
        ex.sendResponseHeaders(200, len);
        try (InputStream in = Files.newInputStream(f); OutputStream os = ex.getResponseBody()) {
            copy(in, os, -1);
        }
    }

    /* ======================= 工具方法 ======================= */

    static void sendHtml(HttpExchange ex, int code, String html, boolean head) throws IOException {
        byte[] out = html.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        h.set("Content-Length", String.valueOf(out.length));
        if (head) { ex.sendResponseHeaders(code, -1); return; }
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); os.flush(); }
    }

    static void sendText(HttpExchange ex, int code, String msg, boolean head) throws IOException {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/plain; charset=utf-8");
        h.set("Content-Length", String.valueOf(out.length));
        if (head) { ex.sendResponseHeaders(code, -1); return; }
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); os.flush(); }
    }

    static void copy(InputStream in, OutputStream os, long limit) throws IOException {
        byte[] buf = new byte[BUF_SIZE];
        long left = limit;
        int n;
        while (left != 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left < 0 ? buf.length : left))) > 0) {
            os.write(buf, 0, n);
            if (left > 0) left -= n;
        }
        os.flush();
    }

    static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) { if (in.read() < 0) return; left--; } else left -= s;
        }
    }

    /** 解析 Range: bytes=start-end / bytes=start- / bytes=-suffix */
    static long[] parseRange(String header, long len) {
        if (header == null || len <= 0) return null;
        String v = header.trim();
        if (!v.regionMatches(true, 0, "bytes=", 0, 6)) return null;
        v = v.substring(6).trim();
        int comma = v.indexOf(',');
        if (comma >= 0) v = v.substring(0, comma).trim();
        int dash = v.indexOf('-');
        if (dash < 0) return null;
        String s = v.substring(0, dash).trim(), e = v.substring(dash + 1).trim();
        try {
            long start, end;
            if (s.isEmpty()) {
                long suffix = Long.parseLong(e);
                if (suffix <= 0) return null;
                start = Math.max(0, len - suffix);
                end = len - 1;
            } else {
                start = Long.parseLong(s);
                end = e.isEmpty() ? len - 1 : Long.parseLong(e);
            }
            if (start < 0 || start > end || start >= len) return null;
            return new long[]{start, Math.min(end, len - 1)};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 自动识别 UTF-8 / GBK / UTF-16，尽可能正确显示中文文本 */
    static Decoded decodeText(byte[] raw) {
        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            off = 3;
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE");
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, off, raw.length - off)).toString();
            return new Decoded(text, off > 0 ? "UTF-8 (BOM)" : "UTF-8");
        } catch (CharacterCodingException e) {
            // 不是合法 UTF-8，按中文 Windows 常见的 GBK 处理
            try {
                return new Decoded(new String(raw, off, raw.length - off, Charset.forName("GBK")), "GBK");
            } catch (Exception e2) {
                return new Decoded(new String(raw, off, raw.length - off, StandardCharsets.ISO_8859_1), "Latin-1");
            }
        }
    }

    static final class Decoded {
        final String text, charset;
        Decoded(String text, String charset) { this.text = text; this.charset = charset; }
    }

    /** RFC 5987：同时给 ASCII 回退名和 UTF-8 文件名，中文名下载不乱码 */
    static String contentDisposition(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "_").replace("\"", "'");
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc;
    }

    /** 逐段百分号编码，保留 '/' */
    static String enc(String decodedPath) {
        StringBuilder sb = new StringBuilder(decodedPath.length() + 16);
        for (byte bt : decodedPath.getBytes(StandardCharsets.UTF_8)) {
            int c = bt & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String htmlPage(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n"
             + "<meta charset=\"utf-8\">\n"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
             + "<title>" + esc(title) + "</title>\n<style>" + CSS + "</style>\n"
             + "</head>\n<body>\n" + body + "\n</body>\n</html>\n";
    }

    static String joinUrl(String dirUrl, String name) {
        return (dirUrl.endsWith("/") ? dirUrl : dirUrl + "/") + name;
    }

    static String parentOf(String url) {
        String p = (url.endsWith("/") && url.length() > 1) ? url.substring(0, url.length() - 1) : url;
        int i = p.lastIndexOf('/');
        if (i <= 0) return "/";
        return p.substring(0, i + 1);
    }

    static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static String iconOf(String ext) {
        switch (ext) {
            case "txt": case "text": case "log": case "md": case "markdown": return "📄";
            case "zip": case "rar": case "7z": case "gz": case "tar": return "🗜️";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp": case "svg": return "🖼️";
            case "mp4": case "mkv": case "avi": case "mov": case "flv": case "wmv": return "🎬";
            case "mp3": case "wav": case "flac": case "aac": case "m4a": return "🎵";
            case "pdf": return "📕";
            case "doc": case "docx": return "📘";
            case "xls": case "xlsx": case "csv": return "📗";
            case "ppt": case "pptx": return "📙";
            case "exe": case "msi": case "bat": case "cmd": case "sh": return "⚙️";
            case "java": case "js": case "ts": case "py": case "c": case "cpp": case "cs": case "go": case "rs": return "💻";
            case "json": case "xml": case "yml": case "yaml": case "ini": case "conf": case "properties": return "🧾";
            default: return "📦";
        }
    }

    static long sizeOf(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0; }
    }

    static long mtimeOf(Path p) {
        try { return Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime().toMillis(); }
        catch (Exception e) { return 0; }
    }

    static String human(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    static String fmtTime(long millis) {
        if (millis <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }
}
