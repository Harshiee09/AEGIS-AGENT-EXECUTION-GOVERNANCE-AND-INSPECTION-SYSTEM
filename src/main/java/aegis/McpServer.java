package aegis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP stdio adapter for the authenticated AEGIS API. */
public final class McpServer {
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiToken;
    private final String baseUrl;
    private boolean initializedResponseSent;
    private boolean initialized;

    private McpServer(String apiToken, String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (!"http".equals(uri.getScheme())
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("AEGIS API URL must be local HTTP");
        }
        this.apiToken = apiToken;
        this.baseUrl = uri.toString().replaceAll("/+$", "");
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("AEGIS_API_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("AEGIS_API_TOKEN is required");
        String baseUrl = System.getenv().getOrDefault("AEGIS_API_URL", "http://127.0.0.1:8080");
        McpServer server = new McpServer(token, baseUrl);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> message = Json.object(line);
                    Object response = server.handle(message);
                    if (response != null) System.out.println(Json.stringify(response));
                } catch (Exception error) {
                    System.err.println("AEGIS MCP request failed: " + safeMessage(error));
                    System.out.println(Json.stringify(errorResponse(null, -32700, "Parse error")));
                }
            }
        }
    }

    private Object handle(Map<String, Object> message) {
        Object id = message.get("id");
        boolean request = message.containsKey("id");
        if (!"2.0".equals(message.get("jsonrpc")) || !(message.get("method") instanceof String method))
            return request ? errorResponse(id, -32600, "Invalid Request") : null;
        try {
            Map<String, Object> params = asObject(message.get("params"));
            if (!method.equals("initialize") && !method.equals("ping") && !initialized) {
                return request ? errorResponse(id, -32002, "Server is not initialized") : null;
            }
            return switch (method) {
                case "initialize" -> initialize(id, params);
                case "notifications/initialized" -> { initialized = initializedResponseSent; yield null; }
                case "ping" -> request ? success(id, Map.of()) : null;
                case "tools/list" -> request ? success(id, Map.of("tools", toolDefinitions())) : null;
                case "tools/call" -> request ? callTool(id, params) : null;
                default -> request ? errorResponse(id, -32601, "Method not found: " + method) : null;
            };
        } catch (IllegalArgumentException error) {
            return request ? errorResponse(id, -32602, safeMessage(error)) : null;
        } catch (Exception error) {
            return request ? errorResponse(id, -32603, safeMessage(error)) : null;
        }
    }

    private Object initialize(Object id, Map<String, Object> params) {
        if (id == null) return null;
        Object requested = params.get("protocolVersion");
        String version = requested instanceof String value
                && List.of(PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05").contains(value)
                ? value : PROTOCOL_VERSION;
        initializedResponseSent = true;
        initialized = false;
        return success(id, Map.of(
                "protocolVersion", version,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "aegis", "version", "1.0.0")));
    }

    private Object callTool(Object id, Map<String, Object> params) throws Exception {
        String name = requiredText(params, "name");
        Map<String, Object> arguments = asObject(params.get("arguments"));
        try {
            Object result;
            switch (name) {
                case "aegis_file_operation" -> {
                    requiredText(arguments, "action");
                    requiredText(arguments, "path");
                    result = api("POST", "/api/operations", arguments);
                }
                case "aegis_get_operation" -> {
                    long opId = requiredId(arguments, "id");
                    result = api("GET", "/api/operations/" + opId, null);
                }
                case "aegis_list_operations" -> result = api("GET", "/api/operations", null);
                case "aegis_list_audit_events" -> {
                    long since = arguments.containsKey("since") ? requiredId(arguments, "since") : 0L;
                    result = api("GET", "/api/events?since=" + since, null);
                }
                default -> { return errorResponse(id, -32602, "Unknown tool: " + name); }
            }
            return success(id, Map.of("content", List.of(Map.of("type", "text", "text", Json.stringify(result))), "isError", false));
        } catch (ToolExecutionException | IllegalArgumentException error) {
            return success(id, Map.of("content", List.of(Map.of("type", "text", "text", safeMessage(error))), "isError", true));
        }
    }

    private Object api(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Object parsed;
        try { parsed = Json.parse(response.body()); }
        catch (IllegalArgumentException error) {
            throw new ToolExecutionException("AEGIS returned an invalid JSON response");
        }
        if (!(parsed instanceof Map<?, ?>))
            throw new ToolExecutionException("AEGIS returned an unexpected response format");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new ToolExecutionException("AEGIS API returned HTTP " + response.statusCode() + ": " + Json.stringify(parsed));
        return parsed;
    }

    private static List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("aegis_file_operation", "Submit a file operation to AEGIS. READ, LIST, and STAT execute under the low-risk policy. Every change requires separate administrator approval; this tool cannot approve it.",
                Map.of("action", prop("string", "READ, LIST, STAT, CREATE, WRITE, APPEND, COPY, MOVE, DELETE, or MKDIR"),
                        "path", prop("string", "Path relative to the configured AEGIS file root"),
                        "destination", prop("string", "Required for COPY and MOVE"),
                        "content", prop("string", "UTF-8 content for CREATE, WRITE, or APPEND"),
                        "contentBase64", prop("string", "Base64 file bytes for CREATE, WRITE, or APPEND"),
                        "expectedSha256", prop("string", "Optional expected current file SHA-256")),
                List.of("action", "path")));
        tools.add(tool("aegis_get_operation", "Get the current state and result for an AEGIS file operation.",
                Map.of("id", prop("integer", "Operation ID")), List.of("id")));
        tools.add(tool("aegis_list_operations", "List recent AEGIS file operations and their approval states.", Map.of(), List.of()));
        tools.add(tool("aegis_list_audit_events", "Read committed AEGIS audit events after an event ID.",
                Map.of("since", prop("integer", "Return events with IDs greater than this value; defaults to zero")), List.of()));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static long requiredId(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Long number) || number < 0)
            throw new IllegalArgumentException(key + " must be a nonnegative integer");
        return number;
    }

    private static String requiredText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("params and arguments must be objects");
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0"); response.put("id", id); response.put("result", result);
        return response;
    }

    private static Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> error = Map.of("code", code, "message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0"); response.put("id", id); response.put("error", error);
        return response;
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? "Request failed" : error.getMessage();
    }

    private static final class ToolExecutionException extends RuntimeException {
        ToolExecutionException(String message) { super(message); }
    }
}
