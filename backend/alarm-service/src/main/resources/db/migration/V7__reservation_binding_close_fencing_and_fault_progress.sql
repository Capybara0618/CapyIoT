ALTER TABLE incident_work_order_reservation
 ADD operation_id VARCHAR(64), ADD work_order_version BIGINT UNSIGNED,
 ADD reconcile_attempts INT UNSIGNED NOT NULL DEFAULT 0,
 ADD next_reconcile_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD UNIQUE KEY uk_reservation_operation(operation_id),
 ADD INDEX ix_reservation_reconcile(status,next_reconcile_at,reservation_id);
-- Existing reservations retain NULL operation_id. Neither absence nor age proves an abort.
ALTER TABLE incident_work_order_gate ADD close_attempt_id VARCHAR(64), ADD closing_at DATETIME(3),
 ADD INDEX ix_closing_gate(gate_status,closing_at,incident_id);
UPDATE incident_work_order_gate SET close_attempt_id=CONCAT('legacy-',UUID()),
 closing_at=updated_at,version=version+1 WHERE gate_status='CLOSING';
CREATE TABLE fault_applied_position (
 device_id VARCHAR(64) NOT NULL,stream_key VARCHAR(255) NOT NULL,event_id VARCHAR(64) NOT NULL,
 session_epoch BIGINT UNSIGNED NOT NULL,sequence_no BIGINT UNSIGNED NOT NULL,
 processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(device_id,stream_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing cursors are authoritative processing progress, including ignored observations.
INSERT INTO fault_applied_position(device_id,stream_key,event_id,session_epoch,sequence_no)
 SELECT device_id,stream_key,'legacy-cursor',session_epoch,sequence_no FROM fault_observation_cursor;
CREATE TABLE fault_pending_review (
 park_id VARCHAR(64) PRIMARY KEY,generation BIGINT UNSIGNED NOT NULL DEFAULT 1,after_incident_id VARCHAR(64),
 observed_at DATETIME(3) NOT NULL,attempts INT UNSIGNED NOT NULL DEFAULT 0,last_error VARCHAR(64),
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fault_consumer_blocker (
 topic VARCHAR(128) NOT NULL,partition_no INT NOT NULL,offset_no BIGINT NOT NULL,
 park_id VARCHAR(64),device_id VARCHAR(64),reason_code VARCHAR(64) NOT NULL,payload_sha256 CHAR(64) NOT NULL,
 retryable BOOLEAN NOT NULL DEFAULT FALSE,deadletter_event_id VARCHAR(64),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),resolved_at DATETIME(3),
 PRIMARY KEY(topic,partition_no,offset_no),INDEX ix_blocker_scope(resolved_at,park_id,device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fault_health_version(device_id VARCHAR(64) PRIMARY KEY,version BIGINT UNSIGNED NOT NULL DEFAULT 1) ENGINE=InnoDB;
INSERT INTO fault_health_version(device_id) SELECT DISTINCT device_id FROM anomaly WHERE device_id IS NOT NULL;

INSERT INTO fault_pending_review(park_id,observed_at) SELECT park_id,MAX(last_observed_at) FROM anomaly GROUP BY park_id;
