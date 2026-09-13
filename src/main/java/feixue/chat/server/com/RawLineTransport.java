package feixue.chat.server.com;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// 普通 TCP 行协议传输层
class RawLineTransport implements ClientTransport {
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    RawLineTransport(Socket socket, InputStream input) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public String readMessage() throws IOException {
        return reader.readLine();
    }

    @Override
    public void sendMessage(String message) throws IOException {
        synchronized (writer) {
            writer.println(message);
            if (writer.checkError()) {
                throw new IOException("Socket write failed");
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
