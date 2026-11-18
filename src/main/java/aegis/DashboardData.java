package aegis;

import java.util.List;

public record DashboardData(int activeAgents, int totalRequests, int pendingRequests,
                            List<ActionRequest> requests) { }
