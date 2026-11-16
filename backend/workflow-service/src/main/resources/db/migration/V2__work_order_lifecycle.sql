ALTER TABLE outbox_event
    ADD COLUMN campus_id VARCHAR(64),
    ADD COLUMN delivery_token CHAR(36),
    ADD COLUMN last_error VARCHAR(100),
    ADD COLUMN retried_by VARCHAR(100),
    ADD COLUMN retried_at TIMESTAMP(3),
    ADD INDEX ix_outbox_stream (topic,message_key,status,id),
    ADD INDEX ix_outbox_dead (campus_id,status,id);

CREATE TABLE workorder_revision (
    campus_id VARCHAR(64) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workorder_alarm_source (
    campus_id VARCHAR(64) NOT NULL,
    alarm_id BIGINT NOT NULL,
    alarm_version BIGINT NOT NULL,
    snapshot_json JSON NOT NULL,
    PRIMARY KEY (campus_id, alarm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    source_alarm_id BIGINT NOT NULL,
    source_business_key VARCHAR(100) NOT NULL UNIQUE,
    source_alarm_no VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    assignee VARCHAR(100),
    created_by VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    verified_alarm_version BIGINT,
    verified_at TIMESTAMP(3),
    closed_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workorder_alarm (campus_id,source_alarm_id),
    INDEX ix_workorder_list (campus_id,status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order_history (
    order_id BIGINT NOT NULL,
    order_version BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    assignee VARCHAR(100),
    actor VARCHAR(100) NOT NULL,
    remark VARCHAR(1000),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id,order_version),
    FOREIGN KEY (order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order_comment (
    order_id BIGINT NOT NULL,
    order_version BIGINT NOT NULL,
    actor VARCHAR(100) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id,order_version),
    FOREIGN KEY (order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order_request (
    campus_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    PRIMARY KEY (campus_id,idempotency_key),
    FOREIGN KEY (order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbox_event (
    consumer_group VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_dead_letter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    source_topic VARCHAR(128) NOT NULL,
    source_partition INT NOT NULL,
    source_offset BIGINT NOT NULL,
    message_key VARCHAR(100),
    body MEDIUMTEXT,
    failure VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DEAD',
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_failed_record (source_topic,source_partition,source_offset),
    INDEX ix_workflow_dead (campus_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
