CREATE TABLE work_order_operation (
 operation_id VARCHAR(64) PRIMARY KEY,campus_id VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL,operation_type VARCHAR(16) NOT NULL,command_type VARCHAR(64) NOT NULL,
 incident_id VARCHAR(64),target_order_id BIGINT,request_hash CHAR(64) NOT NULL,protocol_hash CHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PREPARED',reservation_id VARCHAR(64),work_order_id BIGINT,
 work_order_version BIGINT UNSIGNED,result_snapshot JSON,
 agent_confirmation_id VARCHAR(64),agent_draft_id VARCHAR(64),agent_draft_revision BIGINT,agent_request_hash CHAR(64),
 notification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',notification_attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),last_error VARCHAR(64),
 request_id VARCHAR(128),created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_operation_key(campus_id,idempotency_key),UNIQUE KEY uk_operation_reservation(reservation_id),
 INDEX ix_operation_pending(incident_id,state,operation_id),
 INDEX ix_operation_recovery(state,next_attempt_at,operation_id),
 INDEX ix_operation_notification(notification_status,next_attempt_at,operation_id),
 CHECK(state IN('PREPARED','RESERVED','COMMITTED','ABORTED','RECONCILIATION_REQUIRED')),
 CHECK(operation_type IN('CREATE','REOPEN')),CHECK(notification_status IN('PENDING','ACKNOWLEDGED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE work_order_request ADD operation_id VARCHAR(64),ADD UNIQUE KEY uk_request_operation(operation_id);
-- A completed command receipt is commit proof. Its immutable result/version/binding is preserved.
-- There is deliberately no inferred ABORTED record for requests that have no receipt.
INSERT INTO work_order_operation(operation_id,campus_id,idempotency_key,actor_user_id,operation_type,command_type,
 incident_id,target_order_id,request_hash,protocol_hash,state,reservation_id,work_order_id,work_order_version,
 result_snapshot,agent_confirmation_id,agent_draft_id,agent_draft_revision,agent_request_hash,notification_status,created_at)
 SELECT CONCAT('legacy-',LEFT(SHA2(CONCAT(r.campus_id,'|',r.idempotency_key),256),56)),r.campus_id,r.idempotency_key,r.actor_user_id,
 CASE r.command_type WHEN 'WorkOrderReopened' THEN 'REOPEN' ELSE 'CREATE' END,r.command_type,
 w.incident_id,CASE r.command_type WHEN 'WorkOrderReopened' THEN r.order_id ELSE NULL END,
 r.request_hash,r.request_hash,'COMMITTED',r.reservation_id,r.order_id,
 CAST(JSON_UNQUOTE(JSON_EXTRACT(r.result_snapshot,'$.version')) AS UNSIGNED),r.result_snapshot,
 r.agent_confirmation_id,r.agent_draft_id,r.agent_draft_revision,r.agent_request_hash,
 CASE WHEN w.incident_id IS NULL THEN 'ACKNOWLEDGED' ELSE 'PENDING' END,r.created_at
 FROM work_order_request r JOIN work_order w ON w.id=r.order_id
 WHERE r.command_type IN('WorkOrderCreated','WorkOrderReopened') AND r.actor_user_id IS NOT NULL
 AND r.request_hash IS NOT NULL AND JSON_TYPE(JSON_EXTRACT(r.result_snapshot,'$.version')) IN('INTEGER','DOUBLE')
 AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.result_snapshot,'$.version')) AS SIGNED)>=0
 AND (w.incident_id IS NULL OR r.reservation_id IS NOT NULL);
UPDATE work_order_request r JOIN work_order_operation o ON o.campus_id=r.campus_id AND o.idempotency_key=r.idempotency_key
 SET r.operation_id=o.operation_id;
INSERT IGNORE INTO command_lock(scope_id) SELECT DISTINCT campus_id FROM work_order;

CREATE TABLE work_order_operation_device(operation_id VARCHAR(64) NOT NULL,device_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(operation_id,device_id),FOREIGN KEY(operation_id) REFERENCES work_order_operation(operation_id)) ENGINE=InnoDB;
INSERT INTO work_order_operation_device(operation_id,device_id) SELECT o.operation_id,i.device_id FROM work_order_operation o JOIN work_order_device_item i ON i.work_order_id=o.work_order_id;
