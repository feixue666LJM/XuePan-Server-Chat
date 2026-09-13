package feixue.chat.server.com;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Runs the original HttpExchange file handler on the existing HTTP/TLS socket.
final class WebPanExchange extends HttpExchange {
    private final Socket socket;
    private final String method;
    private final URI uri;
    private final OutputStream body;
    private final Headers request = new Headers();
    private final Headers response = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private int responseCode = -1;

    WebPanExchange(Socket socket, String method, URI uri, Map<String, String> headers) throws IOException {
        this.socket = socket;
        this.method = method;
        this.uri = uri;
        this.body = socket.getOutputStream();
        headers.forEach(request::set);
    }

    public Headers getRequestHeaders() { return request; }
    public Headers getResponseHeaders() { return response; }
    public URI getRequestURI() { return uri; }
    public String getRequestMethod() { return method; }
    public HttpContext getHttpContext() { return null; }
    public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
    public OutputStream getResponseBody() { return body; }
    public InetSocketAddress getRemoteAddress() { return (InetSocketAddress) socket.getRemoteSocketAddress(); }
    public InetSocketAddress getLocalAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
    public int getResponseCode() { return responseCode; }
    public String getProtocol() { return "HTTP/1.1"; }
    public Object getAttribute(String name) { return attributes.get(name); }
    public void setAttribute(String name, Object value) { attributes.put(name, value); }
    public void setStreams(InputStream in, OutputStream out) { throw new UnsupportedOperationException(); }
    public HttpPrincipal getPrincipal() { return null; }
    public void close() { try { socket.close(); } catch (IOException ignored) { } }

    public void sendResponseHeaders(int code, long length) throws IOException {
        if (responseCode != -1) throw new IOException("Response already sent");
        responseCode = code;
        response.set("Connection", "close");
        response.set("Cache-Control", "no-store");
        response.set("X-Content-Type-Options", "nosniff");
        response.set("Referrer-Policy", "no-referrer");
        response.set("X-Frame-Options", "SAMEORIGIN");
        response.set("X-Robots-Tag", "noindex, nofollow, noarchive");
        if (!response.containsKey("Content-Length") && code != 204) {
            response.set("Content-Length", Long.toString(Math.max(0, length)));
        }
        String reason;
        switch (code) {
            case 200: reason = "OK"; break;
            case 206: reason = "Partial Content"; break;
            case 303: reason = "See Other"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            case 416: reason = "Range Not Satisfiable"; break;
            case 503: reason = "Service Unavailable"; break;
            default: reason = "Response";
        }
        StringBuilder out = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, List<String>> entry : response.entrySet()) {
            for (String value : entry.getValue()) out.append(entry.getKey()).append(": ").append(value).append("\r\n");
        }
        out.append("\r\n");
        socket.getOutputStream().write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }
}
