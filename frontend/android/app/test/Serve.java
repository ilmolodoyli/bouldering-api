import dev.bouldering.judge.JudgeServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Serve {
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0]);
        String base = args.length > 1 ? args[1] : null;
        JudgeServer server = new JudgeServer(path -> {
            Path p = assets.resolve(path).normalize();
            if (!p.startsWith(assets) || !Files.isRegularFile(p)) return null;
            return Files.readAllBytes(p);
        }, base);
        server.start();
        System.out.println("SERVING port=" + server.port());
        Thread.currentThread().join();
    }
}
