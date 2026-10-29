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
 * the documented read-only API endpoints to the live backend. Only explicit account registration accepts credentials; score and admin writes remain gated.
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
            new HashSet<>(Arrays.asList("/routes/", "/competitors/", "/results/", "/tournaments/", "/openapi.json"));
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
            String host = null, contentType = null; boolean chunked = false, invalidLength = false; int headerBytes=0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerBytes += line.length(); if(headerBytes>MAX_HEADER_BYTES)throw new IOException();
                int colon = line.indexOf(':');
                if(colon>0 && line.substring(0,colon).equalsIgnoreCase("Host"))host=line.substring(colon+1).trim();
                if(colon>0 && line.substring(0,colon).equalsIgnoreCase("Content-Type"))contentType=line.substring(colon+1).trim();
                if(colon>0 && line.substring(0,colon).equalsIgnoreCase("Transfer-Encoding"))chunked=true;
                if (colon > 0 && line.substring(0,colon).equalsIgnoreCase("X-Judge-Action")) registerHeader = line.substring(colon+1).trim();
                if (colon > 0 && line.substring(0,colon).equalsIgnoreCase("Origin")) origin = line.substring(colon+1).trim();
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { if(contentLength!=0)invalidLength=true; contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException ignored) {invalidLength=true;}
                }
            }
            if ("POST".equals(method) && "/api/auth/register".equals(target)) {
                if(!("127.0.0.1:"+port()).equals(host) || !("http://127.0.0.1:"+port()).equals(origin)
                    || !"register-account".equals(registerHeader) || !"application/json".equals(contentType)
                    || chunked || invalidLength || contentLength<2 || contentLength>2048) {
                    respond(out,403,"application/json","{\"error\":\"registration_request_rejected\"}","no-store");return;
                }
                byte[] body=new byte[contentLength];
                try {int pos=0,n;while(pos<body.length){n=in.read(body,pos,body.length-pos);if(n<0)throw new EOFException();pos+=n;}register(body,out);}
                finally {java.util.Arrays.fill(body,(byte)0);}return;
            }
            if (!"GET".equals(method)) {respond(out,403,"text/plain","Server writes gated pending authenticated RBAC contract",null);return;}
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

    private void register(byte[] body, OutputStream out) throws IOException {
        HttpURLConnection conn=null;
        try {
            java.util.Map<String,Object> data=RegistrationJson.parse(new String(body,StandardCharsets.UTF_8));
            if(data.size()!=2 || !(data.get("username") instanceof String) || !(data.get("password") instanceof String))throw new IOException();
            String name=(String)data.get("username"),password=(String)data.get("password");
            if(name.trim().isEmpty() || name.length()>80 || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length>72) {
                respond(out,400,"application/json","{\"error\":\"invalid_fields\"}","no-store");return;
            }
            URL url=new URL(base+"/auth/register");
            if(!"https".equals(url.getProtocol()) && !("http".equals(url.getProtocol()) && "127.0.0.1".equals(url.getHost())))throw new IOException();
            conn=(HttpURLConnection)url.openConnection();conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(TIMEOUT_MS);conn.setReadTimeout(TIMEOUT_MS);conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Accept","application/json");
            conn.setRequestProperty("ngrok-skip-browser-warning","1");conn.setFixedLengthStreamingMode(body.length);
            try(OutputStream sent=conn.getOutputStream()){sent.write(body);}
            int code=conn.getResponseCode();
            if(code==400 || code==409) {respond(out,409,"application/json","{\"error\":\"name_conflict\"}","no-store");return;}
            if(code!=201)throw new IOException();
            byte[] answer;try(InputStream response=conn.getInputStream()){answer=readCapped(response,4097);}
            if(answer.length>4096)throw new IOException();
            java.util.Map<String,Object> receipt=RegistrationJson.parse(new String(answer,StandardCharsets.UTF_8));
            Object id=receipt.get("id");
            if(receipt.size()!=3 || !(id instanceof Long) || (Long)id<1 || (Long)id>9007199254740991L
                || !name.equals(receipt.get("username")) || !"athlete".equals(receipt.get("role")))throw new IOException();
            String clean="{\"id\":"+id+",\"username\":"+RegistrationJson.quote(name)+",\"role\":\"athlete\"}";
            respond(out,201,"application/json",clean,"no-store");
        } catch(Exception ignored) {
            respond(out,502,"application/json","{\"error\":\"registration_unconfirmed_no_retry\"}","no-store");
        } finally {if(conn!=null)conn.disconnect();}
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
