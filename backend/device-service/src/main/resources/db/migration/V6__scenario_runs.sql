CREATE TABLE scenario_run (
    id CHAR(36) PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    template VARCHAR(40) NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    target_ids JSON NOT NULL,
    duration_seconds INT NOT NULL,
    temperature INT,
    phase VARCHAR(32) NOT NULL,
    end_mode VARCHAR(16),
    started_at TIMESTAMP(3) NOT NULL,
    phase_at TIMESTAMP(3) NOT NULL,
    observed_at TIMESTAMP(3),
    ended_at TIMESTAMP(3),
    FOREIGN KEY (campus_id) REFERENCES campus(id),
    INDEX ix_scenario_active (campus_id,ended_at,started_at),
    INDEX ix_scenario_pending (ended_at,phase,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE scenario_run_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    logged_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    message VARCHAR(255) NOT NULL,
    FOREIGN KEY (run_id) REFERENCES scenario_run(id),
    INDEX ix_scenario_log (run_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE device_event
    ADD COLUMN scenario_run_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE WHEN JSON_TYPE(JSON_EXTRACT(payload_json,'$.scenarioRunId'))='STRING'
            AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.scenarioRunId')))=36
        THEN JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.scenarioRunId')) END
    ) VIRTUAL,
    ADD INDEX ix_event_scenario (campus_id,scenario_run_id,id);
