package aegis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All file actions are confined to a configured, existing root directory. */
public final class SandboxFiles {
    private final Path root;

    public SandboxFiles(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AEGIS_FILE_ROOT must be an existing directory");
        if (Files.isSymbolicLink(root)) throw new IOException("AEGIS_FILE_ROOT cannot be a symbolic link");
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public void validate(FileOperation op) throws IOException {
        resolve(op.path(), op.action().equals("LIST") || op.action().equals("STAT"));
        if (op.destination() != null) resolve(op.destination(), false);
    }

    public Map<String, Object> execute(FileOperation op) throws IOException {
        Path path = resolve(op.path(), op.action().equals("LIST") || op.action().equals("STAT"));
        Path destination = op.destination() == null ? null : resolve(op.destination(), false);
        verifyExpected(path, op.expectedSha256());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", op.action());
        result.put("path", root.relativize(path).toString().replace('\\', '/'));
        switch (op.action()) {
            case "READ" -> {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
                if (Files.size(path) > FileOperation.MAX_CONTENT_BYTES) throw new IOException("File exceeds 1 MiB read limit");
                byte[] bytes = readLimited(path);
                result.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
                result.put("size", bytes.length);
                result.put("sha256", hash(bytes));
            }
            case "LIST" -> {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory");
                List<Map<String, Object>> entries = new ArrayList<>();
                boolean truncated;
                try (var stream = Files.list(path)) {
                    List<Path> children = stream.sorted().limit(201).toList();
                    truncated = children.size() > 200;
                    for (Path child : children.subList(0, Math.min(children.size(), 200))) {
                        entries.add(Map.of("name", child.getFileName().toString(),
                                "type", Files.isSymbolicLink(child) ? "link" :
                                        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file"));
                    }
                }
                result.put("entries", entries);
                result.put("truncated", truncated);
            }
            case "STAT" -> {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Path does not exist");
                result.put("type", Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file");
                result.put("size", Files.size(path));
                result.put("modifiedAt", Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toString());
            }
            case "CREATE" -> {
                requireParent(path);
                Files.write(path, op.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                result.put("size", op.content().length);
            }
            case "WRITE" -> {
                requireParent(path);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Target is not a regular file");
                Path temporary = Files.createTempFile(path.getParent(), ".aegis-", ".tmp");
                try {
                    Files.write(temporary, op.content());
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                result.put("size", op.content().length);
            }
            case "APPEND" -> {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
                try (SeekableByteChannel channel = Files.newByteChannel(path,
                        Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS))) {
                    ByteBuffer bytes = ByteBuffer.wrap(op.content());
                    while (bytes.hasRemaining()) channel.write(bytes);
                }
                result.put("size", Files.size(path));
            }
            case "COPY", "MOVE" -> {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source is not a regular file");
                requireParent(destination);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination already exists");
                if (op.action().equals("COPY")) Files.copy(path, destination, LinkOption.NOFOLLOW_LINKS);
                else Files.move(path, destination);
                result.put("destination", root.relativize(destination).toString().replace('\\', '/'));
            }
            case "DELETE" -> {
                if (path.equals(root)) throw new IOException("Cannot delete the root directory");
                Files.delete(path); // A directory must be empty; recursive deletion is never implicit.
            }
            case "MKDIR" -> {
                requireParent(path);
                Files.createDirectory(path);
            }
            default -> throw new IllegalArgumentException("Unsupported action");
        }
        return result;
    }

    private Path resolve(String input, boolean allowRoot) throws IOException {
        if (input.equals(".") && allowRoot) return root;
        if (input.startsWith("/") || input.startsWith("\\") || input.contains(":"))
            throw new IOException("Path must be relative to AEGIS_FILE_ROOT");
        Path relative;
        try { relative = Path.of(input); }
        catch (RuntimeException error) { throw new IOException("Invalid path", error); }
        if (relative.isAbsolute() || relative.getNameCount() == 0) throw new IOException("Relative path required");
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.equals(".") || name.equals("..") || name.isBlank()) throw new IOException("Dot paths are not allowed");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || (!allowRoot && resolved.equals(root))) throw new IOException("Path escapes file root");
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed");
        }
        return resolved;
    }

    private void requireParent(Path path) throws IOException {
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Parent directory does not exist");
    }

    private void verifyExpected(Path path, String expected) throws IOException {
        if (expected == null) return;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected file is missing");
        if (!hashFile(path).equalsIgnoreCase(expected)) throw new IOException("File changed since request was created");
    }

    private byte[] readLimited(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(FileOperation.MAX_CONTENT_BYTES + 1);
            if (bytes.length > FileOperation.MAX_CONTENT_BYTES) throw new IOException("File exceeds 1 MiB read limit");
            return bytes;
        }
    }

    private static String hashFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            for (int size; (size = in.read(buffer)) != -1;) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
