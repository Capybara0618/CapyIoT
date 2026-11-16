CREATE TABLE campus (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    topology_version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE building (
    id VARCHAR(64) PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_building_name (campus_id,name),
    FOREIGN KEY (campus_id) REFERENCES campus(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE floor (
    id VARCHAR(64) PRIMARY KEY,
    building_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_floor_name (building_id,name),
    FOREIGN KEY (building_id) REFERENCES building(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE area (
    id VARCHAR(64) PRIMARY KEY,
    floor_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_area_name (floor_id,name),
    FOREIGN KEY (floor_id) REFERENCES floor(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE device (
    id VARCHAR(64) PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    device_code VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    building_id VARCHAR(64) NOT NULL,
    floor_id VARCHAR(64) NOT NULL,
    area_id VARCHAR(64) NULL,
    password_hash VARCHAR(128) NOT NULL,
    active_session_id VARCHAR(64) NULL,
    session_epoch BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (campus_id) REFERENCES campus(id),
    FOREIGN KEY (building_id) REFERENCES building(id),
    FOREIGN KEY (floor_id) REFERENCES floor(id),
    FOREIGN KEY (area_id) REFERENCES area(id),
    CONSTRAINT ck_device_type CHECK (type IN ('CAMERA','ACCESS_CONTROL','SWITCH','SENSOR')),
    INDEX ix_device_filter (campus_id,type,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE network_link (
    id VARCHAR(64) PRIMARY KEY,
    campus_id VARCHAR(64) NOT NULL,
    upstream_device_id VARCHAR(64) NOT NULL,
    downstream_device_id VARCHAR(64) NOT NULL,
    link_type VARCHAR(32) NOT NULL DEFAULT 'NETWORK',
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_network_parent (downstream_device_id),
    FOREIGN KEY (campus_id) REFERENCES campus(id),
    FOREIGN KEY (upstream_device_id) REFERENCES device(id),
    FOREIGN KEY (downstream_device_id) REFERENCES device(id),
    CHECK (upstream_device_id <> downstream_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE device_session (
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    session_epoch BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id,session_id),
    UNIQUE KEY uk_session_epoch (device_id,session_epoch),
    FOREIGN KEY (device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE device_state_snapshot (
    device_id VARCHAR(64) PRIMARY KEY,
    connectivity_status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    health_status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    session_epoch BIGINT NOT NULL DEFAULT 0,
    seq BIGINT NOT NULL DEFAULT -1,
    last_heartbeat_at TIMESTAMP(3) NULL,
    last_transition_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (device_id) REFERENCES device(id),
    CHECK (connectivity_status IN ('ONLINE','OFFLINE')),
    CHECK (health_status IN ('NORMAL','ABNORMAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE device_state_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    old_connectivity VARCHAR(16) NOT NULL,
    new_connectivity VARCHAR(16) NOT NULL,
    old_health VARCHAR(16) NOT NULL,
    new_health VARCHAR(16) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    state_version BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    FOREIGN KEY (device_id) REFERENCES device(id),
    INDEX ix_state_history (device_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE device_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    campus_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    session_epoch BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'MQTT',
    processing_status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    envelope_hash CHAR(64) NOT NULL,
    kafka_partition INT NOT NULL,
    kafka_offset BIGINT NOT NULL,
    FOREIGN KEY (device_id) REFERENCES device(id),
    INDEX ix_event_device_time (device_id,received_at,id),
    INDEX ix_event_campus_time (campus_id,received_at,id),
    INDEX ix_event_resume (campus_id,kafka_partition,kafka_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbox_event (
    consumer_group VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
