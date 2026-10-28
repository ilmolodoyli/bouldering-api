import dev.bouldering.judge.JudgeServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** JVM tests for JudgeServer against a scripted mock backend. */
public final class ServerTest {
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " :: " + detail));
        if (!ok) failures++;
    }

    static final class MockBackend implements AutoCloseable {
        final ServerSocket socket;
        final List<String> requests = new ArrayList<>();
        volatile boolean sawSkipHeader;
        volatile String mode = "ok";
        final Thread thread;

        MockBackend() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket c = socket.accept();
                        new Thread(() -> handle(c)).start();
                    } catch (IOException e) { return; }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        private void handle(Socket c) {
            try (Socket s = c) {
                InputStream in = s.getInputStream();
                String requestLine = readLine(in);
                if (requestLine == null) return;
                synchronized (requests) { requests.add(requestLine); }
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("ngrok-skip-browser-warning:")) sawSkipHeader = true;
                }
                byte[] body;
                switch (mode) {
                    case "badjson": body = "<html>not json</html>".getBytes(StandardCharsets.UTF_8); break;
                    case "huge": {
                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < 300000; i++) sb.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append('}');
                        sb.append(']');
                        body = sb.toString().getBytes(StandardCharsets.UTF_8);
                        break;
                    }
                    default: {
                        String path = requestLine.split(" ")[1];
                        if (path.startsWith("/routes/")) body = "[{\"id\":1,\"difficulty\":\"6b\",\"age_category\":\"Adults\"}]".getBytes(StandardCharsets.UTF_8);
                        else if (path.startsWith("/competitors/")) body = "[{\"id\":1,\"full_name\":\"Test One\",\"age\":20,\"category\":\"Men\"}]".getBytes(StandardCharsets.UTF_8);
                        else if (path.startsWith("/openapi.json")) body = "{\"openapi\":\"3.1.0\"}".getBytes(StandardCharsets.UTF_8);
                        else body = "[]".getBytes(StandardCharsets.UTF_8);
                    }
                }
                OutputStream out = s.getOutputStream();
                String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
            } catch (IOException ignored) {}
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                buf.write(b);
            }
            return buf.size() == 0 ? null : buf.toString().trim();
        }

        public void close() throws IOException { socket.close(); }
    }

    static final class Response {
        int code; String headers = ""; byte[] body;
        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }

    static Response request(int port, String method, String path) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            String req = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            Response r = new Response();
            String status = readHttpLine(in);
            r.code = Integer.parseInt(status.split(" ")[1]);
            String line;
            int length = 0;
            while (!(line = readHttpLine(in)).isEmpty()) {
                r.headers += line + "\n";
                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (int i = 0; i < length; i++) {
                int b = in.read();
                if (b == -1) break;
                buf.write(b);
            }
            r.body = buf.toByteArray();
            return r;
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        return buf.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0]);
        JudgeServer.Assets fs = path -> {
            Path p = assets.resolve(path).normalize();
            if (!p.startsWith(assets) || !Files.isRegularFile(p)) return null;
            return Files.readAllBytes(p);
        };

        try (MockBackend backend = new MockBackend()) {
            JudgeServer server = new JudgeServer(fs, "http://127.0.0.1:" + backend.port());
            server.start();
            int port = server.port();

            Response index = request(port, "GET", "/");
            byte[] realIndex = Files.readAllBytes(assets.resolve("index.html"));
            check("GET / serves index.html bytes", index.code == 200 && java.util.Arrays.equals(index.body, realIndex),
                    "code=" + index.code + " len=" + index.body.length + " want=" + realIndex.length);
            check("index content-type", index.headers.toLowerCase().contains("content-type: text/html"), index.headers);

            Response ui = request(port, "GET", "/ui.mjs");
            check("GET /ui.mjs is javascript", ui.code == 200 && ui.headers.toLowerCase().contains("text/javascript"), "code=" + ui.code + " " + ui.headers);

            Response missing = request(port, "GET", "/nope.txt");
            check("missing asset is 404", missing.code == 404, "code=" + missing.code);

            Response traversal = request(port, "GET", "/..%2f..%2fetc");
            check("traversal rejected", traversal.code == 400 || traversal.code == 404, "code=" + traversal.code);

            Response routes = request(port, "GET", "/api/routes/?skip=0&limit=100");
            check("GET /api/routes/ proxied", routes.code == 200 && routes.bodyText().contains("\"difficulty\""),
                    "code=" + routes.code + " body=" + routes.bodyText());
            check("proxy forwarded query", backend.requests.stream().anyMatch(r -> r.contains("/routes/?skip=0&limit=100")),
                    backend.requests.toString());
            check("ngrok skip header sent", backend.sawSkipHeader, "header missing");
            check("api is no-store", routes.headers.toLowerCase().contains("cache-control: no-store"), routes.headers);

            Response notAllowed = request(port, "GET", "/api/not-enabled/");
            check("non-allowlisted endpoint 403", notAllowed.code == 403, "code=" + notAllowed.code);

            Response post = request(port, "POST", "/api/results/");
            check("POST blocked 403", post.code == 403 && post.bodyText().contains("Live scoring blocked"), "code=" + post.code);

            Response put = request(port, "PUT", "/api/routes/");
            check("PUT blocked 403", put.code == 403, "code=" + put.code);

            backend.mode = "badjson";
            Response bad = request(port, "GET", "/api/openapi.json");
            check("invalid JSON is 502", bad.code == 502, "code=" + bad.code + " body=" + bad.bodyText());

            backend.mode = "huge";
            Response huge = request(port, "GET", "/api/openapi.json");
            check("oversize response is 502", huge.code == 502, "code=" + huge.code);

            backend.mode = "ok";
            server.close();

            JudgeServer dead = new JudgeServer(fs, "http://127.0.0.1:1");
            dead.start();
            Response down = request(dead.port(), "GET", "/api/routes/");
            check("unreachable backend is 502", down.code == 502, "code=" + down.code);
            dead.close();
        }

        if (args.length > 1 && args[1].equals("live")) {
            JudgeServer live = new JudgeServer(fs, null);
            live.start();
            System.out.println("LIVE base=" + JudgeServer.DEFAULT_BASE + " port=" + live.port());
            for (String ep : new String[]{"/api/routes/?skip=0&limit=100", "/api/competitors/?skip=0&limit=100"}) {
                Response r = request(live.port(), "GET", ep);
                System.out.println("LIVE " + ep + " -> " + r.code + " bytes=" + r.body.length);
                System.out.println("LIVE body head: " + r.bodyText().substring(0, Math.min(300, r.bodyText().length())));
                check("live " + ep, r.code == 200, "code=" + r.code + " " + r.bodyText());
            }
            live.close();
        }

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
