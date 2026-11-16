ALTER TABLE outbox_event
    ADD COLUMN campus_id VARCHAR(64),
    ADD COLUMN delivery_token CHAR(36),
    ADD COLUMN last_error VARCHAR(100),
    ADD COLUMN retried_by VARCHAR(100),
    ADD COLUMN retried_at TIMESTAMP(3),
    ADD INDEX ix_outbox_stream (topic,message_key,status,id),
    ADD INDEX ix_outbox_dead (campus_id,status,id);
