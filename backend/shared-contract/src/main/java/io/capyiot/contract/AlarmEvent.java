package io.capyiot.contract;

import java.time.Instant;

public record AlarmEvent(String eventId, String eventType, int schemaVersion, String campusId, long alarmId,
        Instant occurredAt, Instant receivedAt, String traceId, String producer, Source payload) {
    public static final String TOPIC = "iot.alarm.events.v1";
    public static final String CHANGED = "ALARM_CHANGED";

    public record Source(long id, String campusId, String alarmNo, String deviceName, String type,
            String severity, String status, String relationKind, Long parentAlarmId, long version, Instant recoveredAt) {
        public boolean recovered() { return "RECOVERED".equals(status); }
        public boolean automatic() {
            return parentAlarmId == null && "NETWORK_INCIDENT".equals(type)
                    && "CONFIRMED_UPSTREAM".equals(relationKind);
        }
    }
}
