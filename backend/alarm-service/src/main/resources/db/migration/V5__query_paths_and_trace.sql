ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE alarm ADD INDEX ix_alarm_campus_recovered (campus_id,status,last_seen_at DESC,id DESC),
    ADD INDEX ix_alarm_main_recovered (campus_id,parent_alarm_id,status,last_seen_at DESC,id DESC);
