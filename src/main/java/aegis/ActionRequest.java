package aegis;

import java.time.LocalDateTime;

public record ActionRequest(
        long id,
        String agentName,
        String action,
        String resource,
        String riskLevel,
        String decisionStatus,
        String executionStatus,
        LocalDateTime createdAt) { }
