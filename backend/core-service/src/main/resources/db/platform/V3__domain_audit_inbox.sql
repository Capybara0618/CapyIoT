CREATE TABLE audit_event_inbox (
 consumer_name VARCHAR(64) NOT NULL,event_id VARCHAR(64) NOT NULL,source_context VARCHAR(32) NOT NULL,
 received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE audit_record ADD source_event_id VARCHAR(64),ADD UNIQUE KEY uk_audit_source_event(source,source_event_id);
