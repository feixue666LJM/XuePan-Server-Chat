package feixue.chat.server.com;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// WebSocket 传输层（RFC 6455 客户端帧解析与服务器帧发送）
class WebSocketTransport implements ClientTransport {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final Object outputLock = new Object();
    private boolean closeFrameSent;
    private ByteArrayOutputStream fragmentedMessage;
    private int fragmentedOpcode = -1;

    WebSocketTransport(Socket socket, InputStream input, OutputStream output) {
        this.socket = socket;
        this.input = input;
        this.output = output;
    }

    @Override
    public String readMessage() throws IOException {
        while (!socket.isClosed()) {
            WebSocketFrame frame = readFrame();
            if (frame == null) {
                return null;
            }
            if (frame.opcode == 0x8) {
                sendCloseFrame(frame.payload.length >= 2 ? frame.payload : closePayload(1000, ""));
                return null;
            }
            if (frame.opcode == 0x9) {
                sendFrame(0xA, frame.payload);
                continue;
            }
            if (frame.opcode == 0xA) {
                continue;
            }
            if (frame.opcode == 0x2) {
                protocolClose(1003, "Binary frames are not supported");
            }
            if (frame.opcode == 0x1) {
                if (fragmentedMessage != null) {
                    protocolClose(1002, "Unexpected data frame");
                }
                if (frame.fin) {
                    return new String(frame.payload, StandardCharsets.UTF_8);
                }
                fragmentedMessage = new ByteArrayOutputStream();
                fragmentedMessage.write(frame.payload, 0, frame.payload.length);
                fragmentedOpcode = frame.opcode;
                continue;
            }
            if (frame.opcode == 0x0) {
                if (fragmentedMessage == null || fragmentedOpcode != 0x1) {
                    protocolClose(1002, "Unexpected continuation frame");
                }
                if (fragmentedMessage.size() + frame.payload.length > ChatServer.MAX_WEBSOCKET_MESSAGE_BYTES) {
                    protocolClose(1009, "Message is too large");
                }
                fragmentedMessage.write(frame.payload, 0, frame.payload.length);
                if (frame.fin) {
                    byte[] complete = fragmentedMessage.toByteArray();
                    fragmentedMessage = null;
                    fragmentedOpcode = -1;
                    return new String(complete, StandardCharsets.UTF_8);
                }
                continue;
            }
            protocolClose(1002, "Unsupported opcode");
        }
        return null;
    }

    private WebSocketFrame readFrame() throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int second = input.read();
        if (second < 0) {
            throw new EOFException("Incomplete WebSocket frame");
        }
        boolean fin = (first & 0x80) != 0;
        if ((first & 0x70) != 0) {
            protocolClose(1002, "RSV bits are not supported");
        }
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        if (!masked) {
            protocolClose(1002, "Client frames must be masked");
        }
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readRequiredByte() << 8) | readRequiredByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                int value = readRequiredByte();
                if (i == 0 && (value & 0x80) != 0) {
                    protocolClose(1002, "Invalid frame length");
                }
                length = (length << 8) | value;
            }
        }
        boolean controlFrame = opcode >= 0x8;
        if ((controlFrame && (!fin || length > 125)) || length > ChatServer.MAX_WEBSOCKET_MESSAGE_BYTES) {
            protocolClose(length > ChatServer.MAX_WEBSOCKET_MESSAGE_BYTES ? 1009 : 1002, "Invalid frame length");
        }
        byte[] mask = new byte[4];
        readFully(mask);
        byte[] payload = new byte[(int) length];
        readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return new WebSocketFrame(fin, opcode, payload);
    }

    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Incomplete WebSocket frame");
        }
        return value;
    }

    private void readFully(byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                throw new EOFException("Incomplete WebSocket frame");
            }
            offset += count;
        }
    }

    @Override
    public void sendMessage(String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        if (payload.length > ChatServer.MAX_WEBSOCKET_MESSAGE_BYTES) {
            throw new IOException("WebSocket message is too large");
        }
        sendFrame(0x1, payload);
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (outputLock) {
            if (socket.isClosed()) {
                throw new EOFException("WebSocket is closed");
            }
            output.write(0x80 | opcode);
            if (payload.length <= 125) {
                output.write(payload.length);
            } else if (payload.length <= 65535) {
                output.write(126);
                output.write((payload.length >>> 8) & 0xFF);
                output.write(payload.length & 0xFF);
            } else {
                output.write(127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) ((length >>> shift) & 0xFF));
                }
            }
            output.write(payload);
            output.flush();
        }
    }

    private void protocolClose(int code, String reason) throws IOException {
        sendCloseFrame(closePayload(code, reason));
        throw new IOException("WebSocket protocol error: " + reason);
    }

    private void sendCloseFrame(byte[] payload) throws IOException {
        if (!closeFrameSent && !socket.isClosed()) {
            closeFrameSent = true;
            sendFrame(0x8, payload);
        }
    }

    private byte[] closePayload(int code, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(reasonBytes.length, 123);
        byte[] payload = new byte[length + 2];
        payload[0] = (byte) ((code >>> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, length);
        return payload;
    }

    @Override
    public void close() throws IOException {
        try {
            sendCloseFrame(closePayload(1000, ""));
        } finally {
            socket.close();
        }
    }
}
