package aegis;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Local Java HTTP API for governed file actions, audit events and Ollama. */
public final class BackendMain {
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private final BackendRepository repository;
    private final SandboxFiles files;
    private final OllamaClient ollama;
    private final String apiToken;
    private final String adminToken;

    private BackendMain(BackendRepository repository, SandboxFiles files, OllamaClient ollama,
                        String apiToken, String adminToken) {
        this.repository = repository;
        this.files = files;
        this.ollama = ollama;
        this.apiToken = apiToken;
        this.adminToken = adminToken;
    }

    public static void main(String[] args) throws Exception {
        String apiToken = requiredSetting("AEGIS_API_TOKEN");
        String adminToken = requiredSetting("AEGIS_ADMIN_TOKEN");
        if (apiToken.equals(adminToken)) throw new IllegalArgumentException("Agent and admin tokens must differ");
        Path root = Path.of(requiredSetting("AEGIS_FILE_ROOT"));
        SandboxFiles files = new SandboxFiles(root);
        BackendRepository repository = new BackendRepository();
        repository.initialize();
        String url = System.getenv().getOrDefault("AEGIS_OLLAMA_URL", "http://127.0.0.1:11434");
        int port = Integer.parseInt(System.getenv().getOrDefault("AEGIS_PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        BackendMain backend = new BackendMain(repository, files, new OllamaClient(url), apiToken, adminToken);
        server.createContext("/", backend::handle);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.start();
        System.out.println("AEGIS Java backend on http://127.0.0.1:" + port
                + " | file root " + files.root());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, messageOr(error, "Invalid request"));
        } catch (IllegalStateException error) {
            sendError(exchange, 409, messageOr(error, "Operation is not in a valid state"));
        } catch (SQLException error) {
            sendError(exchange, 503, "Database unavailable", messageOr(error, "No database detail available"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "Operation interrupted");
        } catch (IOException error) {
            sendError(exchange, 502, messageOr(error, "I/O request failed"));
        } catch (RuntimeException error) {
            error.printStackTrace(System.err);
            sendError(exchange, 500, "Internal backend error");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException, SQLException, InterruptedException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/") && method.equals("GET")) {
            byte[] bytes = BackendPage.HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            return;
        }
        if (path.equals("/favicon.ico") && method.equals("GET")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (path.equals("/health") && method.equals("GET")) {
            json(exchange, 200, Map.of("service", "aegis", "status", "ok", "pid", ProcessHandle.current().pid()));
            return;
        }
        boolean admin = isAuthorized(exchange, adminToken);
        boolean agent = isAuthorized(exchange, apiToken);
        if (!admin && !agent) { json(exchange, 401, Map.of("error", "Bearer token required")); return; }
        if (path.equals("/api/models") && method.equals("GET")) {
            json(exchange, 200, Map.of("models", ollama.models()));
            return;
        }
        if (path.equals("/api/assist") && method.equals("POST")) {
            Map<String, Object> body = body(exchange);
            json(exchange, 200, ollama.assist(string(body, "model"), string(body, "prompt")));
            return;
        }
        if (path.equals("/api/operations") && method.equals("GET")) {
            json(exchange, 200, Map.of("operations", repository.list(100)));
            return;
        }
        if (path.equals("/api/operations") && method.equals("POST")) {
            Map<String, Object> body = body(exchange);
            String configuredAgent = System.getenv().getOrDefault("AEGIS_AGENT_NAME", "AEGIS AI Agent");
            String agentName = body.containsKey("agent") ? string(body, "agent") : configuredAgent;
            if (agentName.length() > 100) throw new IllegalArgumentException("Agent name too long");
            if (!admin && !configuredAgent.equals(agentName)) {
                json(exchange, 403, Map.of("error", "Agent token cannot submit for another agent"));
                return;
            }
            FileOperation operation = FileOperation.fromJson(body);
            try { files.validate(operation); }
            catch (IOException error) {
                throw new IllegalArgumentException(messageOr(error, "Invalid file path"), error);
            }
            long id = repository.create(agentName, operation);
            if (operation.automatic()) {
                FileOperation claimed = repository.approveAndClaim(id, "automatic read policy");
                Map<String, Object> output = execute(id, claimed);
                json(exchange, output.containsKey("error") ? 409 : 201,
                        Map.of("operation", repository.get(id), "output", output));
            } else json(exchange, 202, Map.of("operation", repository.get(id)));
            return;
        }
        if (path.equals("/api/events") && method.equals("GET")) {
            long since = since(exchange);
            json(exchange, 200, Map.of("events", repository.events(since, 200)));
            return;
        }
        if (path.equals("/api/events/stream") && method.equals("GET")) {
            stream(exchange, since(exchange));
            return;
        }
        if (path.startsWith("/api/operations/")) {
            String[] parts = path.substring("/api/operations/".length()).split("/", -1);
            if (parts.length < 1 || parts.length > 2) { json(exchange, 404, Map.of("error", "Not found")); return; }
            long id = parseId(parts[0]);
            if (parts.length == 1 && method.equals("GET")) {
                Map<String, Object> operation = repository.get(id);
                if (operation == null) json(exchange, 404, Map.of("error", "Operation not found"));
                else json(exchange, 200, operation);
                return;
            }
            if (parts.length == 2 && parts[1].equals("analyze") && method.equals("POST")) {
                Map<String, Object> operation = repository.get(id);
                if (operation == null) { json(exchange, 404, Map.of("error", "Operation not found")); return; }
                String model = string(body(exchange), "model");
                String prompt = "Review this recorded AEGIS file request and give concise, specific risks and safeguards. "
                        + "The path has already been validated as relative to AEGIS's sandbox. "
                        + "A destination is required only for COPY and MOVE; null is expected for every other action. "
                        + "The risk label is assigned by AEGIS policy and is authoritative. "
                        + "Do not invent missing fields, assume the file contents, or claim you approved or executed anything. "
                        + "This is a review of the recorded state, which may already be complete. Action: " + operation.get("action")
                        + "; path: " + operation.get("path")
                        + "; destination: " + operation.get("destination")
                        + "; policy risk: " + operation.get("risk")
                        + "; decision: " + operation.get("decision")
                        + "; execution status: " + operation.get("status") + ".";
                Map<String, Object> analysis = ollama.assist(model, prompt);
                repository.recordModelReview(id, model);
                json(exchange, 200, Map.of("operation", operation, "analysis", analysis));
                return;
            }
            if (parts.length == 2 && method.equals("POST") && (parts[1].equals("approve") || parts[1].equals("block"))) {
                if (!admin) { json(exchange, 403, Map.of("error", "Admin token required")); return; }
                if (parts[1].equals("block")) {
                    repository.block(id, "admin");
                    json(exchange, 200, Map.of("operation", repository.get(id)));
                } else {
                    FileOperation claimed = repository.approveAndClaim(id, "admin");
                    Map<String, Object> output = execute(id, claimed);
                    json(exchange, output.containsKey("error") ? 409 : 200,
                            Map.of("operation", repository.get(id), "output", output));
                }
                return;
            }
        }
        json(exchange, 404, Map.of("error", "Not found"));
    }

    private Map<String, Object> execute(long id, FileOperation operation) throws SQLException {
        try {
            Map<String, Object> output;
            synchronized (files) { output = files.execute(operation); }
            repository.finish(id, true, "File " + operation.action() + " completed");
            return output;
        } catch (IOException | RuntimeException error) {
            repository.finish(id, false, "File " + operation.action() + " failed: " + error.getMessage());
            return Map.of("error", error.getMessage() == null ? "File operation failed" : error.getMessage());
        }
    }

    private void stream(HttpExchange exchange, long cursor) throws IOException, SQLException, InterruptedException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            long until = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < until) {
                List<Map<String, Object>> events = repository.events(cursor, 200);
                for (Map<String, Object> event : events) {
                    cursor = ((Number) event.get("id")).longValue();
                    out.write(("id: " + cursor + "\nevent: audit\ndata: " + Json.stringify(event) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (events.isEmpty()) out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1000);
            }
        }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IllegalArgumentException("Request body exceeds 2 MiB");
        return Json.object(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static void sendError(HttpExchange exchange, int code, String message) {
        sendError(exchange, code, message, null);
    }

    private static void sendError(HttpExchange exchange, int code, String message, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (detail != null) body.put("detail", detail);
        try {
            json(exchange, code, body);
        } catch (IOException responseError) {
            System.err.println("Could not send HTTP error response: " + messageOr(responseError, "connection closed"));
        }
    }

    private static String messageOr(Throwable error, String fallback) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static boolean isAuthorized(HttpExchange exchange, String token) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }

    private static long since(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        String query = exchange.getRequestURI().getRawQuery();
        String raw = header != null ? header : query != null && query.startsWith("since=") ? query.substring(6) : "0";
        return parseId(raw);
    }

    private static long parseId(String value) {
        try { long id = Long.parseLong(value); if (id < 0) throw new NumberFormatException(); return id; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid ID"); }
    }

    private static String requiredSetting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
