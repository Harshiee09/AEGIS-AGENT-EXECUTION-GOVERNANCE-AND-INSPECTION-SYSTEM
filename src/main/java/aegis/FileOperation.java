package aegis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record FileOperation(String action, String path, String destination,
                            byte[] content, String expectedSha256) {
    private static final Set<String> ACTIONS = Set.of(
            "READ", "LIST", "STAT", "CREATE", "WRITE", "APPEND", "COPY", "MOVE", "DELETE", "MKDIR");
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;

    public static FileOperation fromJson(Map<String, Object> body) {
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported file action");
        String path = required(body, "path");
        if (path.length() > 255) throw new IllegalArgumentException("path exceeds 255 characters");
        String destination = optional(body, "destination");
        if (destination != null && destination.length() > 255)
            throw new IllegalArgumentException("destination exceeds 255 characters");
        String expectedSha256 = optional(body, "expectedSha256");
        if (expectedSha256 != null && !expectedSha256.matches("(?i)[0-9a-f]{64}"))
            throw new IllegalArgumentException("expectedSha256 must be a SHA-256 hex digest");
        boolean needsDestination = action.equals("COPY") || action.equals("MOVE");
        if (needsDestination != (destination != null))
            throw new IllegalArgumentException("destination is required only for COPY or MOVE");
        Object plain = body.get("content");
        Object encoded = body.get("contentBase64");
        if (plain != null && encoded != null) throw new IllegalArgumentException("Use content or contentBase64, not both");
        byte[] content = null;
        if (plain != null) {
            if (!(plain instanceof String text)) throw new IllegalArgumentException("content must be text");
            content = text.getBytes(StandardCharsets.UTF_8);
        }
        if (encoded != null) {
            if (!(encoded instanceof String text)) throw new IllegalArgumentException("contentBase64 must be text");
            try { content = Base64.getDecoder().decode(text); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid contentBase64"); }
        }
        boolean needsContent = action.equals("CREATE") || action.equals("WRITE") || action.equals("APPEND");
        if (needsContent != (content != null)) throw new IllegalArgumentException("Content is required only for CREATE, WRITE or APPEND");
        if (content != null && content.length > MAX_CONTENT_BYTES) throw new IllegalArgumentException("Content exceeds 1 MiB");
        return new FileOperation(action, path, destination, content, expectedSha256);
    }

    public String risk() {
        return switch (action) {
            case "READ", "LIST", "STAT" -> "LOW";
            case "CREATE", "MKDIR", "COPY" -> "MEDIUM";
            default -> "HIGH";
        };
    }

    public boolean automatic() { return risk().equals("LOW"); }

    private static String required(Map<String, Object> map, String key) {
        String value = optional(map, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String optional(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 1000)
            throw new IllegalArgumentException(key + " must be nonempty text up to 1000 characters");
        return text;
    }
}
