package feixue.chat.server.com;

// WebSocket 帧数据
class WebSocketFrame {
    final boolean fin;
    final int opcode;
    final byte[] payload;

    WebSocketFrame(boolean fin, int opcode, byte[] payload) {
        this.fin = fin;
        this.opcode = opcode;
        this.payload = payload;
    }
}
