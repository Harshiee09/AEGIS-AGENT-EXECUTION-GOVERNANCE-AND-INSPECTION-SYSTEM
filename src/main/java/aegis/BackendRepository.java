package aegis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC persistence for real file operations and the ordered audit feed. */
public final class BackendRepository {
    public void initialize() throws SQLException {
        try (Connection connection = DBConnection.open(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS agents ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(100) NOT NULL UNIQUE, status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE', "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS action_requests ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, agent_id BIGINT UNSIGNED NOT NULL, "
                    + "action_name VARCHAR(80) NOT NULL, target_resource VARCHAR(255) NOT NULL, "
                    + "risk_level ENUM('LOW','MEDIUM','HIGH') NOT NULL, "
                    + "decision_status ENUM('PENDING','APPROVED','BLOCKED') NOT NULL DEFAULT 'PENDING', "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "KEY idx_requests_agent (agent_id), KEY idx_requests_status_created (decision_status, created_at), "
                    + "CONSTRAINT fk_requests_agent FOREIGN KEY (agent_id) REFERENCES agents(id)) ENGINE=InnoDB");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS audit_logs ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, request_id BIGINT UNSIGNED NOT NULL, "
                    + "event_type VARCHAR(60) NOT NULL, details VARCHAR(500) NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "KEY idx_audit_request_created (request_id, created_at), "
                    + "CONSTRAINT fk_audit_request FOREIGN KEY (request_id) REFERENCES action_requests(id)) ENGINE=InnoDB");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS file_operations ("
                    + "request_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, "
                    + "source_path VARCHAR(1000) NOT NULL, destination_path VARCHAR(1000) NULL, "
                    + "content MEDIUMBLOB NULL, expected_sha256 CHAR(64) NULL, "
                    + "execution_status ENUM('PENDING','EXECUTING','EXECUTED','FAILED','BLOCKED') NOT NULL DEFAULT 'PENDING', "
                    + "result_summary VARCHAR(500) NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "KEY idx_file_status_updated (execution_status, updated_at), "
                    + "CONSTRAINT fk_file_request FOREIGN KEY (request_id) REFERENCES action_requests(id)) ENGINE=InnoDB");
            String configuredAgent = System.getenv().getOrDefault("AEGIS_AGENT_NAME", "AEGIS AI Agent");
            try (PreparedStatement agent = connection.prepareStatement(
                    "INSERT INTO agents (name,status) VALUES (?, 'ACTIVE') "
                    + "ON DUPLICATE KEY UPDATE status='ACTIVE'")) {
                agent.setString(1, configuredAgent);
                agent.executeUpdate();
            }
            if (!configuredAgent.equals("Demo Agent")) {
                try (PreparedStatement legacy = connection.prepareStatement(
                        "UPDATE agents SET status='INACTIVE' WHERE name='Demo Agent'")) {
                    legacy.executeUpdate();
                }
            }
        }
    }

    public long create(String agent, FileOperation op) throws SQLException {
        try (Connection connection = DBConnection.open()) {
            connection.setAutoCommit(false);
            try {
                long agentId;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT id FROM agents WHERE name=? AND status='ACTIVE'")) {
                    query.setString(1, agent);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Unknown or inactive agent");
                        agentId = rows.getLong(1);
                    }
                }
                long id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO action_requests (agent_id,action_name,target_resource,risk_level,decision_status) "
                        + "VALUES (?,?,?,?, 'PENDING')", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, agentId);
                    insert.setString(2, "FILE_" + op.action());
                    insert.setString(3, op.path());
                    insert.setString(4, op.risk());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Missing request ID");
                        id = keys.getLong(1);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO file_operations (request_id,source_path,destination_path,content,expected_sha256) "
                        + "VALUES (?,?,?,?,?)")) {
                    insert.setLong(1, id);
                    insert.setString(2, op.path());
                    insert.setString(3, op.destination());
                    insert.setBytes(4, op.content());
                    insert.setString(5, op.expectedSha256());
                    insert.executeUpdate();
                }
                audit(connection, id, "REQUEST_CREATED", "File " + op.action() + " requested by " + agent);
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    /** Claims exactly one pending operation. The caller runs the file action after commit. */
    public FileOperation approveAndClaim(long id, String approver) throws SQLException {
        try (Connection connection = DBConnection.open()) {
            connection.setAutoCommit(false);
            try {
                FileOperation op = null;
                String state = null;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT r.action_name,f.source_path,f.destination_path,f.content,f.expected_sha256,f.execution_status "
                        + "FROM file_operations f JOIN action_requests r ON r.id=f.request_id "
                        + "WHERE f.request_id=? FOR UPDATE")) {
                    query.setLong(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next()) {
                            state = rows.getString(6);
                            op = new FileOperation(rows.getString(1).substring(5), rows.getString(2),
                                    rows.getString(3), rows.getBytes(4), rows.getString(5));
                        }
                    }
                }
                if (op == null) throw new IllegalArgumentException("Operation not found");
                if (!"PENDING".equals(state)) throw new IllegalStateException("Operation is already " + state);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE action_requests SET decision_status='APPROVED' WHERE id=?")) {
                    update.setLong(1, id); update.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE file_operations SET execution_status='EXECUTING' WHERE request_id=?")) {
                    update.setLong(1, id); update.executeUpdate();
                }
                audit(connection, id, "APPROVED", "Approved by " + approver);
                connection.commit();
                return op;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    public void block(long id, String approver) throws SQLException {
        try (Connection connection = DBConnection.open()) {
            connection.setAutoCommit(false);
            try {
                String state;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT execution_status FROM file_operations WHERE request_id=? FOR UPDATE")) {
                    query.setLong(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Operation not found");
                        state = rows.getString(1);
                    }
                }
                if (!"PENDING".equals(state)) throw new IllegalStateException("Operation is already " + state);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE action_requests SET decision_status='BLOCKED' WHERE id=?")) {
                    update.setLong(1, id); update.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE file_operations SET execution_status='BLOCKED',content=NULL WHERE request_id=?")) {
                    update.setLong(1, id); update.executeUpdate();
                }
                audit(connection, id, "BLOCKED", "Blocked by " + approver);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    public void finish(long id, boolean success, String summary) throws SQLException {
        try (Connection connection = DBConnection.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE file_operations SET execution_status=?,result_summary=?,content=NULL "
                        + "WHERE request_id=? AND execution_status='EXECUTING'")) {
                    update.setString(1, success ? "EXECUTED" : "FAILED");
                    update.setString(2, limit(summary));
                    update.setLong(3, id);
                    if (update.executeUpdate() != 1) throw new SQLException("Operation was not executing");
                }
                audit(connection, id, success ? "EXECUTED" : "FAILED", limit(summary));
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    public void recordModelReview(long id, String model) throws SQLException {
        try (Connection connection = DBConnection.open()) {
            audit(connection, id, "MODEL_REVIEWED", "Advisory review by Ollama model " + model);
        }
    }

    public Map<String, Object> get(long id) throws SQLException {
        try (Connection connection = DBConnection.open(); PreparedStatement query = connection.prepareStatement(
                "SELECT r.id,a.name,r.action_name,r.risk_level,r.decision_status,f.execution_status,"
                + "f.source_path,f.destination_path,f.result_summary,r.created_at,f.updated_at "
                + "FROM file_operations f JOIN action_requests r ON r.id=f.request_id "
                + "JOIN agents a ON a.id=r.agent_id WHERE r.id=?")) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? operation(rows) : null; }
        }
    }

    public List<Map<String, Object>> list(int limit) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = DBConnection.open(); PreparedStatement query = connection.prepareStatement(
                "SELECT r.id,a.name,r.action_name,r.risk_level,r.decision_status,f.execution_status,"
                + "f.source_path,f.destination_path,f.result_summary,r.created_at,f.updated_at "
                + "FROM file_operations f JOIN action_requests r ON r.id=f.request_id "
                + "JOIN agents a ON a.id=r.agent_id ORDER BY r.id DESC LIMIT ?")) {
            query.setInt(1, Math.min(Math.max(limit, 1), 200));
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(operation(rows)); }
        }
        return result;
    }

    public List<Map<String, Object>> events(long since, int limit) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = DBConnection.open(); PreparedStatement query = connection.prepareStatement(
                "SELECT id,request_id,event_type,details,created_at FROM audit_logs "
                + "WHERE id>? ORDER BY id ASC LIMIT ?")) {
            query.setLong(1, since);
            query.setInt(2, Math.min(Math.max(limit, 1), 200));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(Map.of(
                        "id", rows.getLong(1), "requestId", rows.getLong(2),
                        "type", rows.getString(3), "details", rows.getString(4),
                        "createdAt", rows.getTimestamp(5).toInstant().toString()));
            }
        }
        return result;
    }

    private static Map<String, Object> operation(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getLong(1));
        result.put("agent", row.getString(2));
        result.put("action", row.getString(3).substring(5));
        result.put("risk", row.getString(4));
        result.put("decision", row.getString(5));
        result.put("status", row.getString(6));
        result.put("path", row.getString(7));
        result.put("destination", row.getString(8));
        result.put("result", row.getString(9));
        result.put("createdAt", row.getTimestamp(10).toInstant().toString());
        result.put("updatedAt", row.getTimestamp(11).toInstant().toString());
        return result;
    }

    private static void audit(Connection connection, long id, String type, String details) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO audit_logs (request_id,event_type,details) VALUES (?,?,?)")) {
            insert.setLong(1, id);
            insert.setString(2, type);
            insert.setString(3, limit(details));
            insert.executeUpdate();
        }
    }

    private static String limit(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 490 ? value.substring(0, 490) : value;
    }

    private static void rollback(Connection connection, Throwable error) {
        try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
    }
}
