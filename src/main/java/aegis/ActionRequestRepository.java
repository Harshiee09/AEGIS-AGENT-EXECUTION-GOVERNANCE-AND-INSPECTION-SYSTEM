package aegis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** All SQL used by the Review 1 dashboard lives here. */
public final class ActionRequestRepository implements DashboardDataSource {
    private static final String DEMO_AGENT = "Demo Agent";
    private static final Sample[] SAMPLES = {
        new Sample("READ_FILE", "reports/quarterly.txt", "LOW"),
        new Sample("QUERY_DATABASE", "customer_records", "MEDIUM"),
        new Sample("DELETE_FILE", "archive/old.txt", "HIGH")
    };

    @Override
    public DashboardData loadDashboard() throws SQLException {
        try (Connection connection = DBConnection.open()) {
            int activeAgents = count(connection,
                    "SELECT COUNT(*) FROM agents WHERE status = 'ACTIVE'");
            int totalRequests = count(connection,
                    "SELECT COUNT(*) FROM action_requests r JOIN agents a ON a.id=r.agent_id WHERE a.status='ACTIVE'");
            int pendingRequests = count(connection,
                    "SELECT COUNT(*) FROM action_requests r JOIN agents a ON a.id=r.agent_id "
                    + "WHERE a.status='ACTIVE' AND r.decision_status = 'PENDING'");
            List<ActionRequest> requests = new ArrayList<>();
            String sql = "SELECT r.id, a.name AS agent_name, r.action_name, "
                    + "r.target_resource, r.risk_level, r.decision_status, "
                    + "COALESCE(f.execution_status,'DEMO') AS execution_status, r.created_at "
                    + "FROM action_requests r JOIN agents a ON a.id = r.agent_id "
                    + "LEFT JOIN file_operations f ON f.request_id=r.id "
                    + "WHERE a.status='ACTIVE' "
                    + "ORDER BY r.id DESC LIMIT 200";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Timestamp created = rows.getTimestamp("created_at");
                    requests.add(new ActionRequest(
                            rows.getLong("id"), rows.getString("agent_name"),
                            rows.getString("action_name"), rows.getString("target_resource"),
                            rows.getString("risk_level"), rows.getString("decision_status"),
                            rows.getString("execution_status"),
                            created.toLocalDateTime()));
                }
            }
            return new DashboardData(activeAgents, totalRequests, pendingRequests, requests);
        }
    }

    /** Inserts one simulated request and its audit event atomically. No tool is executed. */
    public long insertSampleRequest() throws SQLException {
        try (Connection connection = DBConnection.open()) {
            connection.setAutoCommit(false);
            try {
                long agentId = findDemoAgentId(connection);
                int index = count(connection, "SELECT COUNT(*) FROM action_requests") % SAMPLES.length;
                Sample sample = SAMPLES[index];
                long requestId;
                String insertRequest = "INSERT INTO action_requests "
                        + "(agent_id, action_name, target_resource, risk_level, decision_status) "
                        + "VALUES (?, ?, ?, ?, 'PENDING')";
                try (PreparedStatement statement = connection.prepareStatement(
                        insertRequest, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, agentId);
                    statement.setString(2, sample.action());
                    statement.setString(3, sample.resource());
                    statement.setString(4, sample.risk());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Request insert did not affect one row.");
                    }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Request ID was not returned.");
                        requestId = keys.getLong(1);
                    }
                }
                String insertAudit = "INSERT INTO audit_logs (request_id, event_type, details) "
                        + "VALUES (?, 'REQUEST_CREATED', ?)";
                try (PreparedStatement statement = connection.prepareStatement(insertAudit)) {
                    statement.setLong(1, requestId);
                    statement.setString(2, "Simulated request created in Review 1 dashboard");
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Audit insert did not affect one row.");
                    }
                }
                connection.commit();
                return requestId;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        }
    }

    public boolean hasAuditEvent(long requestId) throws SQLException {
        try (Connection connection = DBConnection.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM audit_logs WHERE request_id = ? AND event_type = 'REQUEST_CREATED'")) {
            statement.setLong(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static long findDemoAgentId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM agents WHERE name = ? AND status = 'ACTIVE'")) {
            statement.setString(1, DEMO_AGENT);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Demo Agent is missing or inactive. Run sql/schema.sql.");
                }
                return rows.getLong(1);
            }
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private record Sample(String action, String resource, String risk) { }
}
