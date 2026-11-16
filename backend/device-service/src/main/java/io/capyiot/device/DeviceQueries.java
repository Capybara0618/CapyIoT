package io.capyiot.device;

import static io.capyiot.device.DeviceModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DeviceQueries {
    private static final String FROM = """
            FROM device d JOIN building b ON b.id=d.building_id JOIN floor f ON f.id=d.floor_id
            LEFT JOIN area a ON a.id=d.area_id JOIN device_state_snapshot s ON s.device_id=d.id
            LEFT JOIN network_link n ON n.downstream_device_id=d.id LEFT JOIN device u ON u.id=n.upstream_device_id
            """;
    private static final String COLUMNS = """
            d.*,b.name building_name,f.name floor_name,a.name area_name,
            u.id upstream_id,u.name upstream_name,s.connectivity_status,s.health_status,
            s.last_heartbeat_at,s.version state_version,s.session_epoch state_epoch,s.seq
            """;
    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    DeviceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    DeviceView find(String campusId, String id) {
        List<DeviceView> rows = jdbc.query("SELECT " + COLUMNS + FROM + " WHERE d.campus_id=? AND d.id=?",
                VIEW, campusId, id);
        if (rows.isEmpty()) throw DeviceProblem.notFound("设备不存在");
        return rows.getFirst();
    }

    Page<DeviceView> list(String campusId, String type, String buildingId, String connectivity,
            String health, String keyword, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw DeviceProblem.badRequest("分页参数无效");
        var parameters = new HashMap<String, Object>();
        parameters.put("campus", campusId);
        var where = new StringBuilder(" WHERE d.campus_id=:campus");
        addFilter(where, parameters, "d.type", "type", type, List.of("CAMERA", "ACCESS_CONTROL", "SWITCH", "SENSOR"));
        addFilter(where, parameters, "d.building_id", "building", buildingId, List.of());
        addFilter(where, parameters, "s.connectivity_status", "connectivity", connectivity, List.of("ONLINE", "OFFLINE"));
        addFilter(where, parameters, "s.health_status", "health", health, List.of("NORMAL", "ABNORMAL"));
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 100) throw DeviceProblem.badRequest("关键词过长");
            where.append(" AND (d.name LIKE :keyword ESCAPE '!' OR d.device_code LIKE :keyword ESCAPE '!')");
            parameters.put("keyword", "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        Long total = named.queryForObject("SELECT COUNT(*) " + FROM + where, parameters, Long.class);
        parameters.put("limit", size);
        parameters.put("offset", (page - 1L) * size);
        List<DeviceView> items = named.query("SELECT " + COLUMNS + FROM + where
                + " ORDER BY d.created_at DESC,d.id DESC LIMIT :limit OFFSET :offset", parameters, VIEW);
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    Summary summary(String campusId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) total,COALESCE(SUM(s.connectivity_status='ONLINE'),0) online,
                COALESCE(SUM(s.connectivity_status='OFFLINE'),0) offline,
                COALESCE(SUM(s.health_status='ABNORMAL'),0) abnormal
                FROM device d JOIN device_state_snapshot s ON s.device_id=d.id WHERE d.campus_id=?
                """, (rs, row) -> new Summary(rs.getLong("total"), rs.getLong("online"),
                rs.getLong("offline"), rs.getLong("abnormal")), campusId);
    }

    static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void addFilter(StringBuilder where, Map<String, Object> parameters, String column,
            String key, String value, List<String> allowed) {
        if (value == null || value.isBlank()) return;
        if (!allowed.isEmpty() && !allowed.contains(value)) throw DeviceProblem.badRequest("筛选条件无效");
        where.append(" AND ").append(column).append("=:").append(key);
        parameters.put(key, value);
    }

    private static final RowMapper<DeviceView> VIEW = (rs, row) -> new DeviceView(
            rs.getString("id"), rs.getString("device_code"), Type.valueOf(rs.getString("type")),
            rs.getString("name"), rs.getBoolean("enabled"), rs.getString("campus_id"),
            rs.getString("building_id"), rs.getString("building_name"), rs.getString("floor_id"),
            rs.getString("floor_name"), rs.getString("area_id"), rs.getString("area_name"),
            rs.getString("upstream_id"), rs.getString("upstream_name"), rs.getString("connectivity_status"),
            rs.getString("health_status"), instant(rs, "last_heartbeat_at"), instant(rs, "created_at"),
            instant(rs, "updated_at"), rs.getLong("version"), rs.getLong("state_version"),
            rs.getLong("state_epoch"), rs.getLong("seq"));
}
