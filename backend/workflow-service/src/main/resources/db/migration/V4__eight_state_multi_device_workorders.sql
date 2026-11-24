ALTER TABLE work_order
 MODIFY source_alarm_id BIGINT NULL,MODIFY source_alarm_no VARCHAR(64) NULL,
 ADD work_order_no VARCHAR(64),ADD source_type VARCHAR(16) NOT NULL DEFAULT 'LEGACY_ALARM',ADD incident_id VARCHAR(64),
 ADD legacy_priority VARCHAR(16),ADD priority_reason VARCHAR(1000),ADD description VARCHAR(4000),ADD work_category VARCHAR(64),ADD expected_result VARCHAR(2000),
 ADD assigned_repairer_id VARCHAR(64),ADD designated_acceptor_id VARCHAR(64),ADD work_cycle_no INT UNSIGNED NOT NULL DEFAULT 1,
 ADD due_at DATETIME(3),ADD completion_summary VARCHAR(4000),ADD cancel_reason VARCHAR(1000),ADD last_acceptance_id VARCHAR(64),
 ADD assigned_at DATETIME(3),ADD accepted_at DATETIME(3),ADD started_at DATETIME(3),ADD submitted_at DATETIME(3),ADD cancelled_at DATETIME(3),
 ADD last_reservation_id VARCHAR(64),
 ADD CHECK(status IN('OPEN','ASSIGNED','ACCEPTED','IN_PROGRESS','VERIFYING','RETURNED','CLOSED','CANCELLED')),
 ADD CHECK(source_type IN('LEGACY_ALARM','INCIDENT','MANUAL')),
 ADD CHECK(assigned_repairer_id IS NULL OR designated_acceptor_id IS NULL OR assigned_repairer_id<>designated_acceptor_id),
 ADD CHECK(source_type='LEGACY_ALARM' OR source_type='INCIDENT' AND incident_id IS NOT NULL OR source_type='MANUAL' AND incident_id IS NULL);
UPDATE work_order SET work_order_no=CONCAT('WO-',LPAD(id,6,'0')),legacy_priority=priority;
ALTER TABLE work_order MODIFY work_order_no VARCHAR(64) NOT NULL,
 ADD UNIQUE KEY uk_order_number(campus_id,work_order_no),ADD INDEX ix_order_incident(incident_id,status),ADD UNIQUE KEY uk_order_reservation(last_reservation_id);
CREATE TABLE work_order_device_item (
 item_id VARCHAR(64) PRIMARY KEY,work_order_id BIGINT NOT NULL,device_id VARCHAR(64) NOT NULL,
 asset_code_snapshot VARCHAR(64) NOT NULL,device_name_snapshot VARCHAR(100) NOT NULL,device_type_snapshot VARCHAR(16),area_id_snapshot VARCHAR(64),
 monitoring_point_id VARCHAR(64),device_role VARCHAR(16) NOT NULL,issue_description VARCHAR(2000) NOT NULL,
 main_marker TINYINT GENERATED ALWAYS AS(CASE WHEN device_role='MAIN' THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_order_device(work_order_id,device_id),UNIQUE KEY uk_order_main(work_order_id,main_marker),
 FOREIGN KEY(work_order_id) REFERENCES work_order(id),CHECK(device_role IN('MAIN','RELATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_record (
 record_id VARCHAR(64) PRIMARY KEY,work_order_id BIGINT NOT NULL,work_cycle_no INT UNSIGNED NOT NULL,
 operator_id VARCHAR(64) NOT NULL,record_type VARCHAR(32) NOT NULL,content VARCHAR(4000) NOT NULL,
 evidence_refs_json JSON NOT NULL,device_results_json JSON,recorded_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 corrects_record_id VARCHAR(64),FOREIGN KEY(work_order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE acceptance_record (
 acceptance_id VARCHAR(64) PRIMARY KEY,work_order_id BIGINT NOT NULL,work_cycle_no INT UNSIGNED NOT NULL,acceptor_id VARCHAR(64) NOT NULL,
 result VARCHAR(16) NOT NULL,comment VARCHAR(2000) NOT NULL,check_items_json JSON NOT NULL,evidence_refs_json JSON NOT NULL,
 accepted_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_cycle_acceptance(work_order_id,work_cycle_no),FOREIGN KEY(work_order_id) REFERENCES work_order(id),CHECK(result IN('PASS','RETURN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE work_order_history ADD request_id VARCHAR(128),ADD correlation_id VARCHAR(128);
ALTER TABLE work_order_request MODIFY order_id BIGINT NULL,ADD actor_user_id VARCHAR(64),ADD command_type VARCHAR(64),
 ADD request_hash CHAR(64),ADD result_snapshot JSON,ADD status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
 ADD reservation_id VARCHAR(64),ADD created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD UNIQUE KEY uk_workorder_request_reservation(reservation_id);
UPDATE work_order_request SET request_hash=fingerprint,command_type='LEGACY_CREATE';
-- Historical comments and Alarm IDs stay intact; they are never fabricated repair/acceptance records.
CREATE TABLE command_lock(scope_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB;
