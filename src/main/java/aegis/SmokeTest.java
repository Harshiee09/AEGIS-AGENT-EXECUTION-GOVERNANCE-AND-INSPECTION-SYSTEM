package aegis;

/** Runs the real database round trip without opening a GUI. */
public final class SmokeTest {
    private SmokeTest() { }

    public static void main(String[] args) throws Exception {
        ActionRequestRepository repository = new ActionRequestRepository();
        int before = repository.loadDashboard().totalRequests();
        long id = repository.insertSampleRequest();
        DashboardData after = repository.loadDashboard();
        boolean visible = after.requests().stream().anyMatch(request ->
                request.id() == id && "PENDING".equals(request.decisionStatus()));
        if (after.totalRequests() != before + 1 || !visible || !repository.hasAuditEvent(id)) {
            throw new AssertionError("Database round trip failed for request " + id);
        }
        System.out.println("PASS: request " + id
                + " inserted, audited, and read back for JTable; total " + after.totalRequests());
    }
}
