CREATE TABLE topology_checkpoint (
    campus_id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL,
    alarm_revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE topology_projection (
    campus_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    device_code VARCHAR(64) NOT NULL,
    device_type VARCHAR(32) NOT NULL,
    building_id VARCHAR(64) NOT NULL,
    building_name VARCHAR(100) NOT NULL,
    upstream_node_id VARCHAR(64) NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (campus_id,device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alarm_device_version (
    campus_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (campus_id,device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbox_event (
    consumer_group VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE raw_event (
    event_id VARCHAR(64) PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    cause_event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    upstream_node_id VARCHAR(64),
    topology_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    INDEX ix_raw_device (campus_id,device_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    building_id VARCHAR(64) NOT NULL,
    alarm_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    relation_kind VARCHAR(24) NOT NULL,
    parent_alarm_id BIGINT NULL,
    upstream_node_id VARCHAR(64) NULL,
    active_marker TINYINT NULL,
    suppressed_count INT NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMP(3) NOT NULL,
    incident_at TIMESTAMP(3) NOT NULL,
    last_seen_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    acknowledged_at TIMESTAMP(3) NULL,
    acknowledged_by VARCHAR(100) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_alarm_active (campus_id,device_id,alarm_type,active_marker),
    INDEX ix_alarm_list (campus_id,parent_alarm_id,status,last_seen_at),
    INDEX ix_alarm_window (campus_id,upstream_node_id,alarm_type,active_marker,incident_at),
    KEY ix_alarm_children (parent_alarm_id,active_marker),
    FOREIGN KEY (parent_alarm_id) REFERENCES alarm(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alarm_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alarm_id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uk_alarm_evidence (alarm_id,event_id,evidence_type),
    FOREIGN KEY (alarm_id) REFERENCES alarm(id),
    FOREIGN KEY (event_id) REFERENCES raw_event(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alarm_device_link (
    alarm_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    impact_kind VARCHAR(16) NOT NULL,
    first_observed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (alarm_id,device_id),
    FOREIGN KEY (alarm_id) REFERENCES alarm(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
