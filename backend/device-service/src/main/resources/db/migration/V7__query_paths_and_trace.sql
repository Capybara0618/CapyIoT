ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55), ADD COLUMN delivery_token CHAR(36);
ALTER TABLE device ADD INDEX ix_device_campus_created (campus_id,created_at DESC,id DESC),
    ADD INDEX ix_device_building_created (campus_id,building_id,created_at DESC,id DESC);
