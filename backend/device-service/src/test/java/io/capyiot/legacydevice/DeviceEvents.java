package io.capyiot.legacydevice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceEvents {
    record EventView(long id, String eventId, String deviceId, String deviceCode, String deviceName,
            String eventType, String source, String processingStatus, Instant occurredAt,
            Instant receivedAt, String traceId, long sessionEpoch, long seq, JsonNode payload,
            int partition, long offset) {}
    record EventPage(List<EventView> items, long total, int page, int size,
            String resumeCursor, String nextCursor, boolean hasMore) {}
    private static final String FROM = " FROM device_event e JOIN device d ON d.id=e.device_id ";
    private static final String SELECT = "SELECT e.*,d.device_code,d.name device_name";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final RowMapper<EventView> rowMapper;

    DeviceEvents(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.rowMapper = (rs, row) -> new EventView(rs.getLong("id"), rs.getString("event_id"), rs.getString("device_id"),
                rs.getString("device_code"), rs.getString("device_name"), rs.getString("event_type"), rs.getString("source"),
                rs.getString("processing_status"), DeviceQueries.instant(rs, "occurred_at"), DeviceQueries.instant(rs, "received_at"),
                rs.getString("trace_id"), rs.getLong("session_epoch"), rs.getLong("seq"), parsePayload(rs.getString("payload_json")),
                rs.getInt("kafka_partition"), rs.getLong("kafka_offset"));
    }

    @Transactional(readOnly = true)
    public EventPage list(String campusId, String deviceId, String type, String status,
            Instant from, Instant to, int page, int size, String cursor, String scenarioRunId) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw DeviceProblem.badRequest("分页参数无效");
        var parameters = new HashMap<String, Object>();
        parameters.put("campus", campusId);
        var where = new StringBuilder(" WHERE e.campus_id=:campus");
        filter(where, parameters, "e.device_id", "device", deviceId);
        if (type != null && !type.isBlank()) io.capyiot.contract.DeviceMessage.Type.valueOf(type);
        if (status != null && !status.isBlank() && !List.of("PROCESSED", "IGNORED_STALE", "IGNORED_SESSION", "IGNORED_DISABLED").contains(status))
            throw DeviceProblem.badRequest("事件处理状态无效");
        filter(where, parameters, "e.event_type", "type", type);
        filter(where, parameters, "e.processing_status", "status", status);
        filter(where, parameters, "e.scenario_run_id", "scenario", scenarioRunId);
        if (from != null) { where.append(" AND e.received_at>=:from"); parameters.put("from", Timestamp.from(from)); }
        if (to != null) { where.append(" AND e.received_at<=:to"); parameters.put("to", Timestamp.from(to)); }
        if (from != null && to != null && from.isAfter(to)) throw DeviceProblem.badRequest("开始时间不能晚于结束时间");
        long total = Objects.requireNonNull(named.queryForObject("SELECT COUNT(*)" + FROM + where, parameters, Long.class));
        Map<Integer, Long> read = cursor == null ? new TreeMap<>() : decode(cursor);
        if (cursor != null && !read.isEmpty()) {
            var alternatives = new ArrayList<String>();
            for (var entry : read.entrySet()) {
                String key = "p" + entry.getKey();
                alternatives.add("(e.kafka_partition=:" + key + " AND e.kafka_offset>:o" + entry.getKey() + ")");
                parameters.put(key, entry.getKey());
                parameters.put("o" + entry.getKey(), entry.getValue());
            }
            parameters.put("known", read.keySet());
            where.append(" AND (e.kafka_partition NOT IN (:known) OR ")
                    .append(String.join(" OR ", alternatives)).append(")");
        }
        parameters.put("limit", size + 1);
        parameters.put("offset", cursor == null ? (page - 1L) * size : 0L);
        String order = cursor == null ? " DESC" : " ASC";
        var items = named.query(SELECT + FROM + where + " ORDER BY e.id" + order + " LIMIT :limit OFFSET :offset", parameters, rowMapper);
        boolean more = items.size() > size;
        if (more) items = new ArrayList<>(items.subList(0, size));
        Map<Integer, Long> watermark = watermarks(campusId);
        for (EventView item : items) read.merge(item.partition(), item.offset(), Math::max);
        String resume = encode(watermark);
        String next = cursor == null || !more ? resume : encode(read);
        return new EventPage(items, total, page, size, resume, next, more);
    }

    EventView find(String campusId, String eventId) {
        var rows = jdbc.query(SELECT + FROM + " WHERE e.campus_id=? AND e.event_id=?", rowMapper, campusId, eventId);
        if (rows.isEmpty()) throw DeviceProblem.notFound("事件不存在");
        return rows.getFirst();
    }

    List<EventView> scenarioEvidence(String campus, String runId) {
        return jdbc.query(SELECT + FROM + """
                WHERE e.campus_id=? AND e.scenario_run_id=? AND e.event_type<>'HEARTBEAT'
                ORDER BY e.id DESC LIMIT 12
                """, rowMapper, campus, runId);
    }

    private Map<Integer, Long> watermarks(String campusId) {
        var result = new TreeMap<Integer, Long>();
        jdbc.query("SELECT kafka_partition,MAX(kafka_offset) watermark FROM device_event WHERE campus_id=? GROUP BY kafka_partition",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getInt(1), rs.getLong(2)), campusId);
        return result;
    }

    private Map<Integer, Long> decode(String cursor) {
        try {
            if (cursor.length() > 4096) throw new IllegalArgumentException();
            Map<Integer, Long> values = json.readValue(Base64.getUrlDecoder().decode(cursor), new TypeReference<TreeMap<Integer, Long>>() {});
            if (values.size() > 128 || values.entrySet().stream().anyMatch(e -> e.getKey() < 0 || e.getKey() > 1023 || e.getValue() == null || e.getValue() < -1))
                throw new IllegalArgumentException();
            return values;
        } catch (Exception error) { throw DeviceProblem.badRequest("事件游标无效，请重新查询"); }
    }

    private String encode(Map<Integer, Long> values) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(values)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private JsonNode parsePayload(String value) {
        try { return json.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("Stored event JSON is invalid", error); }
    }

    private void filter(StringBuilder where, Map<String, Object> parameters, String column, String key, String value) {
        if (value != null && !value.isBlank()) { where.append(" AND ").append(column).append("=:").append(key); parameters.put(key, value); }
    }
}
