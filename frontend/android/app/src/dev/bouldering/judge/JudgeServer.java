package dev.bouldering.judge;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device port of serve.py: serves the bundled static app and proxies
 * the documented read-only API endpoints to the live backend. No live
 * writes and no credentials are accepted, exactly like the desktop server.
 * Pure Java (no Android imports) so the same code is unit-tested on the JVM.
 */
public final class JudgeServer {
    public interface Assets {
        /** Returns file bytes or null when the path does not exist. */
        byte[] read(String path) throws IOException;
    }

    public static final String DEFAULT_BASE = "https://agile-sandbag-viewless.ngrok-free.dev";
    public static final int PREFERRED_PORT = 8781;
    private static final Set<String> READ_ENDPOINTS =
            new HashSet<>(Arrays.asList("/routes/", "/competitors/", "/results/", "/openapi.json"));
    private static final int MAX_API_BYTES = 2_000_000;
    private static final int TIMEOUT_MS = 12_000;
    private static final int MAX_HEADER_BYTES = 16_384;

    private final ServerSocket socket;
    private final Assets assets;
    private final String base;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = true;

    public JudgeServer(Assets assets, String baseUrl) throws IOException {
        this.assets = assets;
        this.base = validateBase(baseUrl);
        this.socket = bind();
    }

    private static String validateBase(String baseUrl) {
        String b = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        try {
            URI u = new URI(b);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    || u.getHost() == null || u.getUserInfo() != null) {
                throw new IllegalArgumentException("API base must be an http(s) origin without credentials");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API base must be an http(s) origin without credentials");
        }
        return b;
    }

    private static ServerSocket bind() throws IOException {
        // Fixed origin keeps localStorage stable across restarts. Never silently
        // switch ports and make existing results appear lost.
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PREFERRED_PORT), 16);
        return s;
    }

    public int port() { return socket.getLocalPort(); }

    public void start() {
        pool.execute(() -> {
            while (running) {
                try {
                    final Socket client = socket.accept();
                    pool.execute(() -> serve(client));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                    return;
                }
            }
        });
    }

    public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        try { pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(TIMEOUT_MS);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length != 3) { respond(out, 400, "text/plain; charset=utf-8", "Bad request", null); return; }
            String method = parts[0];
            String target = parts[1];
            // Consume headers; only Content-Length matters (to drain a body we reject anyway).
            int contentLength = 0;
            String registerHeader = "";
            String origin = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0,colon).equalsIgnoreCase("X-Judge-Action")) registerHeader = line.substring(colon+1).trim();
                if (colon > 0 && line.substring(0,colon).equalsIgnoreCase("Origin")) origin = line.substring(colon+1).trim();
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if ("POST".equals(method) && (target.equals("/api/competitors/") || target.equals("/api/routes/"))) {
                if (!(target.equals("/api/competitors/")?"register-competitor":"create-route").equals(registerHeader) || !("http://127.0.0.1:" + port()).equals(origin) || contentLength < 1 || contentLength > 8192) {
                    respond(out,403,"text/plain","Registration requires same-origin explicit action",null);return;
                }
                byte[] body = new byte[contentLength]; int read=0;
                while(read<contentLength){int n=in.read(body,read,contentLength-read);if(n<0)throw new EOFException();read+=n;}
                if(!JsonCheck.isValid(new String(body,StandardCharsets.UTF_8))){respond(out,400,"text/plain","Invalid JSON",null);return;}
                register(target.substring(4),body,out);return;
            }
            if (contentLength > 0) skipFully(in, Math.min(contentLength,8192));
            handle(method, target, out);
        } catch (IOException ignored) {
            // Client went away mid-request; nothing useful to send.
        }
    }

    private void handle(String method, String target, OutputStream out) throws IOException {
        String path = target;
        int q = path.indexOf('?');
        String query = q >= 0 ? path.substring(q) : "";
        if (q >= 0) path = path.substring(0, q);

        if (path.startsWith("/api/")) {
            handleApi(method, path.substring(4), query, out);
            return;
        }
        if (!"GET".equals(method)) { respond(out, 403, "text/plain; charset=utf-8", "Not supported", null); return; }
        String rel = decodePath(path);
        if (rel == null) { respond(out, 400, "text/plain; charset=utf-8", "Bad path", null); return; }
        if (rel.equals("/")) rel = "/index.html";
        if (rel.contains("..")) { respond(out, 400, "text/plain; charset=utf-8", "Bad path", null); return; }
        byte[] body;
        try { body = assets.read(rel.substring(1)); }
        catch (IOException e) { respond(out, 500, "text/plain; charset=utf-8", "Asset error", null); return; }
        if (body == null) { respond(out, 404, "text/plain; charset=utf-8", "Not found", null); return; }
        respond(out, 200, contentType(rel), body, null);
    }

    private void handleApi(String method, String endpoint, String query, OutputStream out) throws IOException {
        if ("POST".equals(method)) {
            respond(out, 403, "text/plain; charset=utf-8",
                    "Live scoring blocked: missing auth and authoritative result readback", null);
            return;
        }
        if (!"GET".equals(method)) {
            respond(out, 403, "text/plain; charset=utf-8", "Not supported by current backend", null);
            return;
        }
        if (!READ_ENDPOINTS.contains(endpoint)) {
            respond(out, 403, "text/plain; charset=utf-8", "Only documented read endpoints enabled", null);
            return;
        }
        try {
            byte[] data = fetchApi(endpoint + query);
            respond(out, 200, "application/json", data, "no-store");
        } catch (Exception e) {
            respond(out, 502, "text/plain; charset=utf-8", "Backend not reachable or invalid JSON", null);
        }
    }

    private void register(String endpoint, byte[] body, OutputStream out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)new URL(base+endpoint).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Content-Type","application/json");
        conn.setRequestProperty("ngrok-skip-browser-warning","1");
        try {
            try(OutputStream stream=conn.getOutputStream()){stream.write(body);}
            int code=conn.getResponseCode();
            InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream();
            byte[] data=stream==null?new byte[0]:readCapped(stream,MAX_API_BYTES+1);
            if(code<200||code>=300||data.length>MAX_API_BYTES||!JsonCheck.isValid(new String(data,StandardCharsets.UTF_8)))throw new IOException();
            respond(out,200,"application/json",data,"no-store");
        }catch(Exception e){respond(out,502,"text/plain","Registration outcome unknown; refresh list before retry",null);}
        finally{conn.disconnect();}
    }

    private byte[] fetchApi(String pathAndQuery) throws IOException {
        URL url = new URL(base + pathAndQuery);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("ngrok-skip-browser-warning", "1");
        conn.setRequestProperty("User-Agent", "BoulderingJudgeDev/0.1");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IOException("empty response");
            byte[] data = readCapped(in, MAX_API_BYTES + 1);
            if (data.length > MAX_API_BYTES) throw new IOException("response too large");
            if (code >= 400) throw new IOException("backend HTTP " + code);
            if (!JsonCheck.isValid(new String(data, StandardCharsets.UTF_8))) {
                throw new IOException("invalid JSON");
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".mjs") || path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String decodePath(String path) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.length(); i++) {
                char ch = path.charAt(i);
                if (ch == '%' && i + 2 < path.length() + 1 && i + 2 <= path.length() - 1) {
                    int hi = Character.digit(path.charAt(i + 1), 16);
                    int lo = Character.digit(path.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) return null;
                    sb.append((char) (hi * 16 + lo));
                    i += 2;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b, total = 0;
        while ((b = in.read()) != -1) {
            if (++total > MAX_HEADER_BYTES) throw new IOException("header too large");
            if (b == '\n') {
                byte[] arr = buf.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') len--;
                return new String(arr, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static void skipFully(InputStream in, int n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0 && in.read() == -1) throw new EOFException();
            if (skipped > 0) left -= skipped; else left--;
        }
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk, 0, Math.min(chunk.length, cap - buf.size()))) != -1) {
            buf.write(chunk, 0, n);
            if (buf.size() >= cap) break;
        }
        return buf.toByteArray();
    }

    private static void respond(OutputStream out, int code, String contentType, String message, String cache)
            throws IOException {
        respond(out, code, contentType, message.getBytes(StandardCharsets.UTF_8), cache);
    }

    private static void respond(OutputStream out, int code, String contentType, byte[] body, String cache)
            throws IOException {
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 500 ? "Internal Server Error" : "Bad Gateway";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        if (cache != null) head.append("Cache-Control: ").append(cache).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Minimal strict JSON syntax check, mirroring serve.py's json.loads validation. */
    static final class JsonCheck {
        static boolean isValid(String s) {
            int[] pos = {0};
            boolean ok = value(s, pos);
            skipWs(s, pos);
            return ok && pos[0] == s.length();
        }
        private static void skipWs(String s, int[] p) {
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p[0]++; else break;
            }
        }
        private static boolean value(String s, int[] p) {
            skipWs(s, p);
            if (p[0] >= s.length()) return false;
            char c = s.charAt(p[0]);
            switch (c) {
                case '{': return object(s, p);
                case '[': return array(s, p);
                case '"': return string(s, p);
                case 't': return lit(s, p, "true");
                case 'f': return lit(s, p, "false");
                case 'n': return lit(s, p, "null");
                default: return number(s, p);
            }
        }
        private static boolean lit(String s, int[] p, String word) {
            if (s.regionMatches(p[0], word, 0, word.length())) { p[0] += word.length(); return true; }
            return false;
        }
        private static boolean number(String s, int[] p) {
            int start = p[0];
            if (p[0] < s.length() && s.charAt(p[0]) == '-') p[0]++;
            if (p[0] >= s.length()) return false;
            if (s.charAt(p[0]) == '0') { p[0]++; }
            else if (Character.isDigit(s.charAt(p[0]))) { while (p[0] < s.length() && Character.isDigit(s.charAt(p[0]))) p[0]++; }
            else return false;
            if (p[0] < s.length() && s.charAt(p[0]) == '.') {
                p[0]++;
                if (p[0] >= s.length() || !Character.isDigit(s.charAt(p[0]))) return false;
                while (p[0] < s.length() && Character.isDigit(s.charAt(p[0]))) p[0]++;
            }
            if (p[0] < s.length() && (s.charAt(p[0]) == 'e' || s.charAt(p[0]) == 'E')) {
                p[0]++;
                if (p[0] < s.length() && (s.charAt(p[0]) == '+' || s.charAt(p[0]) == '-')) p[0]++;
                if (p[0] >= s.length() || !Character.isDigit(s.charAt(p[0]))) return false;
                while (p[0] < s.length() && Character.isDigit(s.charAt(p[0]))) p[0]++;
            }
            return p[0] > start;
        }
        private static boolean string(String s, int[] p) {
            p[0]++; // opening quote
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]++);
                if (c == '"') return true;
                if (c == '\\') {
                    if (p[0] >= s.length()) return false;
                    char esc = s.charAt(p[0]++);
                    if (esc == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (p[0] >= s.length() || Character.digit(s.charAt(p[0]++), 16) < 0) return false;
                        }
                    } else if ("\"\\/bfnrt".indexOf(esc) < 0) return false;
                } else if (c < 0x20) return false;
            }
            return false;
        }
        private static boolean array(String s, int[] p) {
            p[0]++;
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return true; }
            while (true) {
                if (!value(s, p)) return false;
                skipWs(s, p);
                if (p[0] >= s.length()) return false;
                char c = s.charAt(p[0]++);
                if (c == ']') return true;
                if (c != ',') return false;
            }
        }
        private static boolean object(String s, int[] p) {
            p[0]++;
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return true; }
            while (true) {
                skipWs(s, p);
                if (p[0] >= s.length() || s.charAt(p[0]) != '"') return false;
                if (!string(s, p)) return false;
                skipWs(s, p);
                if (p[0] >= s.length() || s.charAt(p[0]++) != ':') return false;
                if (!value(s, p)) return false;
                skipWs(s, p);
                if (p[0] >= s.length()) return false;
                char c = s.charAt(p[0]++);
                if (c == '}') return true;
                if (c != ',') return false;
            }
        }
    }
    }
