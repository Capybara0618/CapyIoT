CREATE TABLE device_runtime_state (
 device_id VARCHAR(64) PRIMARY KEY, connectivity_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 operating_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', data_freshness VARCHAR(16) NOT NULL DEFAULT 'NO_DATA',
 session_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0, last_heartbeat_received_at DATETIME(3), heartbeat_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
 last_business_data_received_at DATETIME(3), version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(device_id) REFERENCES device(id),
 CHECK(connectivity_status IN ('UNKNOWN','ONLINE','OFFLINE')), CHECK(operating_mode IN ('UNKNOWN','NORMAL','LOW_POWER')),
 CHECK(data_freshness IN ('NO_DATA','FRESH','STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO device_runtime_state(device_id,connectivity_status,session_epoch,last_heartbeat_received_at,version)
 SELECT s.device_id,s.connectivity_status,s.session_epoch,s.last_heartbeat_at,s.version FROM device_state_snapshot s JOIN device d ON d.id=s.device_id WHERE NOT d.legacy_only;
CREATE TABLE device_stream_cursor (
 device_id VARCHAR(64) NOT NULL, session_epoch BIGINT UNSIGNED NOT NULL, stream_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_sequence BIGINT UNSIGNED NOT NULL, last_event_id VARCHAR(64) NOT NULL, last_envelope_hash CHAR(64) NOT NULL,
 received_at DATETIME(3) NOT NULL, PRIMARY KEY(device_id,session_epoch,stream_key), FOREIGN KEY(device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Keep the old table for legacy consumers. Its single sequence/health columns are not target facts.
CREATE TABLE device_status_history (
 history_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, device_id VARCHAR(64) NOT NULL, state_dimension VARCHAR(32) NOT NULL,
 old_value VARCHAR(32) NOT NULL, new_value VARCHAR(32) NOT NULL, source_event_id VARCHAR(64),
 occurred_at DATETIME(3) NOT NULL, received_at DATETIME(3) NOT NULL, stream_key VARCHAR(255), stream_sequence BIGINT UNSIGNED,
 state_version BIGINT UNSIGNED NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(device_id) REFERENCES device(id), INDEX ix_status_history(device_id,state_dimension,received_at,history_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE device_event ADD schema_version VARCHAR(16), ADD stream_key VARCHAR(255), ADD sequence_no BIGINT UNSIGNED;
