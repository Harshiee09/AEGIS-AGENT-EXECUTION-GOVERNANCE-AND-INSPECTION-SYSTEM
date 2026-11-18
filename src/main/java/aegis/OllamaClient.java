package aegis;

import java.io.IOException;
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

/** Uses the local Ollama HTTP API; no Java model SDK is required. */
public final class OllamaClient {
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public OllamaClient(String url) {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Ollama URL must be local HTTP");
        base = uri;
    }

    public List<Map<String, Object>> models() throws IOException, InterruptedException {
        Map<String, Object> response = request("GET", "/api/tags", null, Duration.ofSeconds(10));
        Object entries = response.get("models");
        List<Map<String, Object>> result = new ArrayList<>();
        if (entries instanceof List<?> models) for (Object entry : models) {
            if (entry instanceof Map<?, ?> model) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", model.get("name"));
                item.put("size", model.get("size"));
                item.put("modifiedAt", model.get("modified_at"));
                result.add(item);
            }
        }
        return result;
    }

    public Map<String, Object> assist(String model, String prompt) throws IOException, InterruptedException {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (prompt == null || prompt.isBlank() || prompt.length() > 8000)
            throw new IllegalArgumentException("prompt must be 1 to 8000 characters");
        boolean installed = models().stream().anyMatch(item -> model.equals(item.get("name")));
        if (!installed) throw new IllegalArgumentException("Model is not installed in local Ollama");
        Map<String, Object> body = Map.of(
                "model", model, "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", "You assist with file operations in AEGIS. Give clear advice. You cannot approve or execute actions. Never claim that an operation was performed."),
                        Map.of("role", "user", "content", prompt)));
        Map<String, Object> response = request("POST", "/api/chat", body, Duration.ofSeconds(120));
        Object message = response.get("message");
        if (!(message instanceof Map<?, ?> map) || !(map.get("content") instanceof String answer))
            throw new IOException("Ollama returned a response without message content");
        return Map.of("model", model, "answer", answer);
    }

    private Map<String, Object> request(String method, String path, Object body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new IOException("Ollama returned HTTP " + response.statusCode());
        try {
            return Json.object(response.body());
        } catch (IllegalArgumentException error) {
            throw new IOException("Ollama returned an invalid JSON response", error);
        }
    }
}
