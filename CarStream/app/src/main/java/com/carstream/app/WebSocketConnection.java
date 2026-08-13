package com.carstream.app;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketConnection {
    public interface Listener {
        void onText(WebSocketConnection connection, String text);
        void onClosed(WebSocketConnection connection);
    }

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final Listener listener;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object writeLock = new Object();

    private WebSocketConnection(Socket socket, InputStream input, OutputStream output, Listener listener) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.listener = listener;
    }

    public static WebSocketConnection accept(Socket socket, InputStream input, OutputStream output,
                                             Map<String, String> headers, Listener listener)
            throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) throw new IOException("Missing WebSocket key");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.getEncoder().encodeToString(
                sha1.digest((key.trim() + MAGIC).getBytes(StandardCharsets.ISO_8859_1)));
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        return new WebSocketConnection(socket, input, output, listener);
    }

    public void readLoop() {
        try {
            while (open.get()) {
                int first = input.read();
                if (first < 0) throw new EOFException();
                int second = readRequired(input);
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7f;
                if (length == 126) {
                    length = ((long) readRequired(input) << 8) | readRequired(input);
                } else if (length == 127) {
                    length = ByteBuffer.wrap(readFully(input, 8)).getLong();
                }
                if (length < 0 || length > 4_194_304) throw new IOException("Frame too large");
                byte[] mask = masked ? readFully(input, 4) : null;
                byte[] payload = readFully(input, (int) length);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x8) break;
                if (opcode == 0x9) sendFrame(0xA, payload);
                else if (opcode == 0x1) listener.onText(this, new String(payload, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly();
            listener.onClosed(this);
        }
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (!open.get()) throw new IOException("WebSocket is closed");
            output.write(0x80 | opcode);
            int length = payload.length;
            if (length <= 125) output.write(length);
            else if (length <= 65_535) {
                output.write(126);
                output.write((length >>> 8) & 0xff);
                output.write(length & 0xff);
            } else {
                output.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) (((long) length >>> shift) & 0xff));
                }
            }
            output.write(payload);
            output.flush();
        }
    }

    public boolean isOpen() { return open.get() && !socket.isClosed(); }

    public void closeQuietly() {
        if (open.compareAndSet(true, false)) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static int readRequired(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        return data;
    }
}
