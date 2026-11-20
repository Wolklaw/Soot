package dev.wolklaw.soot;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * One TLS 1.3 connection to the PC. Every frame is a 4-byte big-endian length and then the bytes:
 * UTF-8 JSON for messages, raw bytes for the one binary frame that follows each file.chunk.
 */
final class LinkConnection implements Closeable {
    static final int MAX_JSON = 1 << 20, MAX_CHUNK = 65536;

    /** The TLS layer refused this phone's certificate: the PC no longer has it among its paired phones. */
    static final class Forgotten extends IOException { Forgotten(String m) { super(m); } }

    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int nextId = 1;
    final String host;
    final int port;

    private LinkConnection(SSLSocket s, String host, int port) throws IOException {
        socket = s;
        this.host = host;
        this.port = port;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536 + 4));
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536 + 4));
    }

    /** Connects and checks the PC's certificate against its pinned fingerprint before anything is sent. */
    static LinkConnection open(Context c, String host, int port, String pcFp, boolean presentOwn) throws Exception {
        SSLSocket s = (SSLSocket) LinkKeys.context(c, pcFp, presentOwn).getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setEnabledProtocols(new String[] { "TLSv1.3" });
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            s.startHandshake();
            return new LinkConnection(s, host, port);
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
            throw refused(e);
        }
    }

    /**
     * In TLS 1.3 the PC checks the phone's certificate after the phone thinks the handshake is done, so a
     * refusal shows up as an alert on the first read. These alerts mean "I don't know this certificate".
     */
    static Exception refused(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (m.matches("(?s).*(unknown_ca|bad_certificate|certificate_unknown|certificate_required|alert_unknown_ca|bad certificate|certificate required|unknown ca).*"))
                return new Forgotten(t.getMessage());
        }
        return e;
    }

    void setReadTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

    /** Sends a message, giving it the next id unless it has one. Returns the id. */
    int send(JSONObject m) throws IOException {
        synchronized (out) {
            try {
                if (!m.has("id")) m.put("id", nextId++);
                writeFrame(m.toString().getBytes(StandardCharsets.UTF_8), 0, -1);
                out.flush();
                return m.getInt("id");
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }
    }

    /** A file.chunk and its binary frame, written together so no other message can come between them. */
    void sendChunk(JSONObject m, byte[] data, int len) throws IOException {
        synchronized (out) {
            try { if (!m.has("id")) m.put("id", nextId++); } catch (org.json.JSONException e) { throw new IOException(e); }
            writeFrame(m.toString().getBytes(StandardCharsets.UTF_8), 0, -1);
            writeFrame(data, 0, len);
            out.flush();
        }
    }

    private void writeFrame(byte[] b, int off, int len) throws IOException {
        if (len < 0) len = b.length;
        if (len > MAX_JSON) throw new IOException("message too big");
        out.writeInt(len);
        out.write(b, off, len);
    }

    /** Reads one frame of at most max bytes. A bigger one is a protocol error and ends the connection. */
    byte[] readFrame(int max) throws IOException {
        int n;
        try {
            n = in.readInt();
        } catch (IOException e) {
            Exception r = refused(e);
            throw r instanceof IOException ? (IOException) r : e;
        }
        if (n < 0 || n > max) throw new IOException("frame of " + n + " bytes is over the limit");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    JSONObject readMessage() throws IOException {
        try {
            return new JSONObject(new String(readFrame(MAX_JSON), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("the PC sent something that is not JSON");
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
