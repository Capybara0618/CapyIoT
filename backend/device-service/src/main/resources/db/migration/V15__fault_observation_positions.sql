CREATE TABLE device_fault_position (
 device_id VARCHAR(64) NOT NULL,stream_key VARCHAR(255) NOT NULL,
 session_epoch BIGINT UNSIGNED NOT NULL,sequence_no BIGINT UNSIGNED NOT NULL,
 event_id VARCHAR(64) NOT NULL,source_outbox_id BIGINT NOT NULL,
 PRIMARY KEY(device_id,stream_key),INDEX ix_fault_position_event(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve the latest committed relevant facts, including those still pending publication.
-- Old telemetry formats without the versioned domain envelope are not reinterpreted.
INSERT INTO device_fault_position(device_id,stream_key,session_epoch,sequence_no,event_id,source_outbox_id)
SELECT device_id,stream_key,session_epoch,sequence_no,event_id,id FROM (
 SELECT facts.*,ROW_NUMBER() OVER(PARTITION BY device_id,stream_key ORDER BY id DESC) AS position_rank FROM (
  SELECT id,aggregate_id AS device_id,event_id,
   CASE WHEN event_type IN('DeviceConnectionLost','DeviceConnectionRestored') THEN 'CONNECTIVITY'
    ELSE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.body.streamKey')) END AS stream_key,
   CASE WHEN event_type IN('DeviceConnectionLost','DeviceConnectionRestored') THEN 0
    ELSE CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.body.sessionEpoch')) AS UNSIGNED) END AS session_epoch,
   CASE WHEN event_type IN('DeviceConnectionLost','DeviceConnectionRestored')
    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.body.stateVersion')) AS UNSIGNED)
    ELSE CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.body.sequence')) AS UNSIGNED) END AS sequence_no
  FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.sourceContext'))='device'
   AND event_type IN('DeviceConnectionLost','DeviceConnectionRestored','DeviceObservationAccepted')
 ) facts WHERE stream_key IS NOT NULL AND session_epoch IS NOT NULL AND sequence_no IS NOT NULL
) ranked WHERE position_rank=1;
