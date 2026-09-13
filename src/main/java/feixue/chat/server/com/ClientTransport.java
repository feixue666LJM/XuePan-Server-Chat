package feixue.chat.server.com;

import java.io.IOException;

// 客户端连接传输层抽象：普通 TCP 行协议与 WebSocket 协议
interface ClientTransport {
    String readMessage() throws IOException;
    void sendMessage(String message) throws IOException;
    void close() throws IOException;
}
