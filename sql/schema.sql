CREATE DATABASE IF NOT EXISTS aegis
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE aegis;

CREATE TABLE IF NOT EXISTS agents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_agents_name (name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS action_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    action_name VARCHAR(80) NOT NULL,
    target_resource VARCHAR(255) NOT NULL,
    risk_level ENUM('LOW', 'MEDIUM', 'HIGH') NOT NULL,
    decision_status ENUM('PENDING', 'APPROVED', 'BLOCKED') NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_requests_agent (agent_id),
    KEY idx_requests_status_created (decision_status, created_at),
    CONSTRAINT fk_requests_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    details VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_request_created (request_id, created_at),
    CONSTRAINT fk_audit_request FOREIGN KEY (request_id) REFERENCES action_requests(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS file_operations (
    request_id BIGINT UNSIGNED NOT NULL,
    source_path VARCHAR(1000) NOT NULL,
    destination_path VARCHAR(1000) NULL,
    content MEDIUMBLOB NULL,
    expected_sha256 CHAR(64) NULL,
    execution_status ENUM('PENDING', 'EXECUTING', 'EXECUTED', 'FAILED', 'BLOCKED') NOT NULL DEFAULT 'PENDING',
    result_summary VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id),
    KEY idx_file_status_updated (execution_status, updated_at),
    CONSTRAINT fk_file_request FOREIGN KEY (request_id) REFERENCES action_requests(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

INSERT INTO agents (name, status) VALUES ('AEGIS AI Agent', 'ACTIVE')
    ON DUPLICATE KEY UPDATE status = 'ACTIVE';
