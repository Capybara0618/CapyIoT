CREATE TABLE anomaly (
 anomaly_id VARCHAR(64) PRIMARY KEY, park_id VARCHAR(64) NOT NULL, subject_kind VARCHAR(32) NOT NULL,
 subject_id VARCHAR(64) NOT NULL, device_id VARCHAR(64), anomaly_type VARCHAR(64) NOT NULL, resource_key VARCHAR(128) NOT NULL,
 active_dedupe_key CHAR(64), status VARCHAR(16) NOT NULL, severity VARCHAR(16) NOT NULL,
 first_observed_at DATETIME(3) NOT NULL, last_observed_at DATETIME(3) NOT NULL, recovered_at DATETIME(3),
 observation_count BIGINT UNSIGNED NOT NULL DEFAULT 1, last_evidence_ref VARCHAR(64), previous_anomaly_id VARCHAR(64),
 resolution_reason VARCHAR(1000), version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_active_anomaly(park_id,active_dedupe_key), INDEX ix_anomaly_device(device_id,status,last_observed_at),
 CHECK(status IN('ACTIVE','RECOVERED','DISMISSED')),CHECK(severity IN('WARNING','FAULT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE anomaly_evidence (
 evidence_id VARCHAR(64) PRIMARY KEY, anomaly_id VARCHAR(64) NOT NULL,source_context VARCHAR(32) NOT NULL,
 source_type VARCHAR(64) NOT NULL,source_id VARCHAR(64) NOT NULL,event_id VARCHAR(64),evidence_type VARCHAR(32) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,received_at DATETIME(3) NOT NULL,summary_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_anomaly_evidence(anomaly_id,source_type,source_id,evidence_type),FOREIGN KEY(anomaly_id) REFERENCES anomaly(anomaly_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE incident (
 incident_id VARCHAR(64) PRIMARY KEY,incident_no VARCHAR(64) NOT NULL,park_id VARCHAR(64) NOT NULL,title VARCHAR(160) NOT NULL,
 status VARCHAR(32) NOT NULL,priority VARCHAR(2) NOT NULL,priority_reason VARCHAR(1000) NOT NULL,root_cause_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 candidate_root_device_id VARCHAR(64),confirmed_root_cause_text VARCHAR(2000),impact_snapshot_json JSON NOT NULL,
 primary_correlation_key VARCHAR(128),opened_at DATETIME(3) NOT NULL,confirmed_at DATETIME(3),closed_at DATETIME(3),
 resolved_disposition VARCHAR(64),created_by VARCHAR(64) NOT NULL,confirmed_by VARCHAR(64),closed_by VARCHAR(64),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_incident_number(park_id,incident_no),UNIQUE KEY uk_incident_correlation(park_id,primary_correlation_key),
 CHECK(status IN('PENDING_REVIEW','OPEN','PROCESSING','PENDING_CLOSE','CLOSED','REJECTED')),CHECK(priority IN('P1','P2','P3','P4')),
 CHECK(root_cause_status IN('UNKNOWN','UNCONFIRMED','CONFIRMED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE incident_anomaly_link (
 link_id VARCHAR(64) PRIMARY KEY,incident_id VARCHAR(64) NOT NULL,anomaly_id VARCHAR(64) NOT NULL,
 relation_kind VARCHAR(32) NOT NULL,confidence DECIMAL(5,4) NOT NULL,linked_at DATETIME(3) NOT NULL,linked_by VARCHAR(64) NOT NULL,
 unlinked_at DATETIME(3),unlinked_by VARCHAR(64),evidence_json JSON NOT NULL,
 active_marker TINYINT GENERATED ALWAYS AS(CASE WHEN unlinked_at IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_anomaly_primary_incident(anomaly_id,active_marker),UNIQUE KEY uk_incident_anomaly(incident_id,anomaly_id,active_marker),
 FOREIGN KEY(incident_id) REFERENCES incident(incident_id),FOREIGN KEY(anomaly_id) REFERENCES anomaly(anomaly_id),CHECK(confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE correlation_candidate (
 candidate_id VARCHAR(64) PRIMARY KEY,incident_id VARCHAR(64),candidate_root_device_id VARCHAR(64),
 related_anomaly_ids_json JSON NOT NULL,confidence DECIMAL(5,4) NOT NULL,evidence_path_json JSON NOT NULL,
 generated_at DATETIME(3) NOT NULL,expires_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE incident_work_order_gate (
 incident_id VARCHAR(64) PRIMARY KEY,gate_status VARCHAR(16) NOT NULL DEFAULT 'OPEN',terminal_reason VARCHAR(1000),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(incident_id) REFERENCES incident(incident_id),CHECK(gate_status IN('OPEN','CLOSING','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE incident_work_order_reservation (
 reservation_id VARCHAR(64) PRIMARY KEY,incident_id VARCHAR(64) NOT NULL,request_id VARCHAR(128) NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,actor_user_id VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,operation_type VARCHAR(16) NOT NULL,work_order_id BIGINT,
 status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),last_reconciled_at DATETIME(3),failure_reason VARCHAR(1000),version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 UNIQUE KEY uk_reservation_request(incident_id,request_id),UNIQUE KEY uk_reservation_key(incident_id,idempotency_key),
 FOREIGN KEY(incident_id) REFERENCES incident(incident_id),CHECK(operation_type IN('CREATE','REOPEN')),
 CHECK(status IN('RESERVED','COMMITTED','RELEASED','RECONCILIATION_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fault_observation_cursor (
 device_id VARCHAR(64) NOT NULL,stream_key VARCHAR(255) NOT NULL,session_epoch BIGINT UNSIGNED NOT NULL,
 sequence_no BIGINT UNSIGNED NOT NULL,received_at DATETIME(3) NOT NULL,PRIMARY KEY(device_id,stream_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fault_device_observation(device_id VARCHAR(64) PRIMARY KEY,last_good_observed_at DATETIME(3) NOT NULL) ENGINE=InnoDB;
CREATE TABLE incident_work_order_projection (
 work_order_id BIGINT PRIMARY KEY,incident_id VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,aggregate_version BIGINT UNSIGNED NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(incident_id) REFERENCES incident(incident_id)
) ENGINE=InnoDB;
CREATE TABLE legacy_alarm_mapping (
 legacy_alarm_id BIGINT NOT NULL,target_type VARCHAR(16) NOT NULL,target_id VARCHAR(64) NOT NULL,
 mapping_reason VARCHAR(1000) NOT NULL,mapped_by VARCHAR(64) NOT NULL,mapped_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(legacy_alarm_id,target_type,target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Preserve old Alarm facts for inspection. No guessed root cause, priority or Incident association.
INSERT INTO anomaly(anomaly_id,park_id,subject_kind,subject_id,device_id,anomaly_type,resource_key,active_dedupe_key,status,severity,first_observed_at,last_observed_at,recovered_at,resolution_reason)
 SELECT CONCAT('LEGACY-ALARM-',id),campus_id,'DEVICE',device_id,device_id,alarm_type,'legacy',
 CASE WHEN active_marker=1 THEN SHA2(CONCAT('LEGACY|',id),256) ELSE NULL END,
 CASE WHEN active_marker=1 THEN 'ACTIVE' ELSE 'RECOVERED' END,
 CASE WHEN severity IN('CRITICAL','HIGH') THEN 'FAULT' ELSE 'WARNING' END,first_seen_at,last_seen_at,recovered_at,'Historical Alarm mapping; inspect original evidence'
 FROM alarm;
INSERT INTO legacy_alarm_mapping(legacy_alarm_id,target_type,target_id,mapping_reason,mapped_by)
 SELECT id,'ANOMALY',CONCAT('LEGACY-ALARM-',id),'Legacy continuous alarm interval only; no Incident/root-cause inference','migration' FROM alarm;
CREATE TABLE command_lock(scope_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE command_request (
 scope_id VARCHAR(64) NOT NULL,idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL,command_type VARCHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,result_snapshot JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(scope_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
