-- Additive cutover repair for rows written by the legacy application after V8–V11.
-- Keep IDs, password hashes and all historical event/state/Alarm/WorkOrder records.
UPDATE device SET device_type=CASE type WHEN 'CAMERA' THEN 'IPC' ELSE type END,
 device_secret_hash=COALESCE(device_secret_hash,password_hash),legacy_only=FALSE,
 area_id=COALESCE(area_id,floor_id,building_id),
 asset_status=CASE WHEN enabled THEN 'IN_SERVICE' ELSE 'OUT_OF_SERVICE' END
 WHERE device_type IS NULL AND type IN('CAMERA','IPC','NVR','SWITCH');
UPDATE device SET legacy_only=TRUE WHERE type IN('SENSOR','ACCESS_CONTROL');
ALTER TABLE outbox_event ADD campus_id VARCHAR(64),ADD last_error VARCHAR(100),
 ADD retried_by VARCHAR(100),ADD retried_at TIMESTAMP(3),ADD INDEX ix_outbox_dead(campus_id,status,id);
UPDATE outbox_event o JOIN device d ON d.id=o.aggregate_id SET o.campus_id=d.campus_id WHERE o.campus_id IS NULL;
