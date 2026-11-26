CREATE TABLE outbox_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
 event_type VARCHAR(64) NOT NULL, event_id VARCHAR(64) NOT NULL,
 topic VARCHAR(128) NOT NULL, message_key VARCHAR(64) NOT NULL, payload_json JSON NOT NULL,
 campus_id VARCHAR(64), trace_parent VARCHAR(55), delivery_token VARCHAR(36),
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), sent_at TIMESTAMP(3),
 last_error VARCHAR(100), retried_by VARCHAR(100), retried_at TIMESTAMP(3),
 UNIQUE KEY uk_outbox_event_id(event_id),
 INDEX ix_outbox_delivery(status,next_retry_at), INDEX ix_outbox_dead(campus_id,status,id),
 CHECK(status IN('PENDING','SENT','RETRY','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
