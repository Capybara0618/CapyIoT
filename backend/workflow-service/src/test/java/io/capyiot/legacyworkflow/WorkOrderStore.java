package io.capyiot.legacyworkflow;

import static io.capyiot.legacyworkflow.WorkOrderModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.AlarmEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class WorkOrderStore {
    private static final String FROM = """
            FROM work_order o JOIN workorder_alarm_source s
                ON s.campus_id=o.campus_id AND s.alarm_id=o.source_alarm_id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    WorkOrderStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void lockCampus(String campus) {
        jdbc.update("INSERT IGNORE INTO workorder_revision(campus_id) VALUES (?)", campus);
        jdbc.queryForObject("SELECT revision FROM workorder_revision WHERE campus_id=? FOR UPDATE", Long.class, campus);
    }

    void changed(String campus) {
        jdbc.update("UPDATE workorder_revision SET revision=revision+1 WHERE campus_id=?", campus);
    }

    long revision(String campus) {
        return jdbc.queryForList("SELECT revision FROM workorder_revision WHERE campus_id=?", Long.class, campus)
                .stream().findFirst().orElse(0L);
    }

    boolean observe(AlarmEvent.Source source) {
        Long previous = jdbc.queryForList("""
                SELECT alarm_version FROM workorder_alarm_source WHERE campus_id=? AND alarm_id=?
                """, Long.class, source.campusId(), source.id()).stream().findFirst().orElse(null);
        if (previous != null && previous >= source.version()) return false;
        jdbc.update("""
                INSERT INTO workorder_alarm_source(campus_id,alarm_id,alarm_version,snapshot_json)
                VALUES (?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE alarm_version=incoming.alarm_version,snapshot_json=incoming.snapshot_json
                """, source.campusId(), source.id(), source.version(), encode(source));
        return true;
    }

    AlarmEvent.Source source(String campus, long id) {
        return jdbc.query("SELECT snapshot_json FROM workorder_alarm_source WHERE campus_id=? AND alarm_id=?",
                (rs, row) -> decode(rs.getString(1)), campus, id).stream().findFirst().orElse(null);
    }

    Order bySource(String campus, long source) {
        return query("o.source_alarm_id", campus, source);
    }

    Order require(String campus, long id) {
        Order order = query("o.id", campus, id);
        if (order == null) throw WorkOrderProblem.missing("工单不存在");
        return order;
    }

    private Order query(String field, String campus, long id) {
        return jdbc.query("SELECT o.*,s.snapshot_json " + FROM + " WHERE o.campus_id=? AND " + field + "=?",
                this::order, campus, id).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<Order> list(String campus, Status status, Priority priority, String assignee, int page, int size) {
        checkPage(page, size);
        var where = new StringBuilder(" WHERE o.campus_id=?");
        var args = new ArrayList<Object>();
        args.add(campus);
        if (status != null) { where.append(" AND o.status=?"); args.add(status.name()); }
        if (priority != null) { where.append(" AND o.priority=?"); args.add(priority.name()); }
        if (assignee != null && !assignee.isBlank()) { where.append(" AND o.assignee=?"); args.add(assignee); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM work_order o" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1L) * size);
        var rows = jdbc.query("SELECT o.*,s.snapshot_json " + FROM + where
                + " ORDER BY o.updated_at DESC,o.id DESC LIMIT ? OFFSET ?", this::order, args.toArray());
        return new Page<>(rows, total, page, size);
    }

    Summary summary(String campus) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) total,COALESCE(SUM(status IN ('OPEN','ASSIGNED')),0) pending,
                  COALESCE(SUM(status='IN_PROGRESS'),0) processing,
                  COALESCE(SUM(status='VERIFYING'),0) verifying,COALESCE(SUM(status='CLOSED'),0) closed
                FROM work_order WHERE campus_id=?
                """, (rs, row) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                campus);
    }

    Page<Entry> timeline(long id, int page, int size) {
        checkPage(page, size);
        long total = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM work_order_history WHERE order_id=?)
                    +(SELECT COUNT(*) FROM work_order_comment WHERE order_id=?)
                """, Long.class, id, id);
        var rows = jdbc.query("""
                SELECT * FROM (
                    SELECT order_version,action,from_status,to_status,actor,remark,created_at,assignee
                    FROM work_order_history WHERE order_id=?
                    UNION ALL
                    SELECT order_version,'ADD_NOTE',NULL,NULL,actor,content,created_at,NULL
                    FROM work_order_comment WHERE order_id=?
                ) timeline ORDER BY order_version ASC LIMIT ? OFFSET ?
                """, (rs, row) -> new Entry(rs.getLong(1), rs.getString(2), status(rs.getString(3)), status(rs.getString(4)),
                rs.getString(5), rs.getString(6), time(rs, "created_at"), rs.getString(8)),
                id, id, size, (page - 1L) * size);
        return new Page<>(rows, total, page, size);
    }

    static void checkPage(int page, int size) {
        if (page < 1 || page > 100_000 || size < 1 || size > 100) {
            throw WorkOrderProblem.invalid("分页参数无效");
        }
    }

    private Order order(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        return new Order(id, "WO-%06d".formatted(id), rs.getLong("source_alarm_id"), rs.getString("source_alarm_no"),
                rs.getString("title"), Priority.valueOf(rs.getString("priority")), Status.valueOf(rs.getString("status")),
                rs.getString("assignee"), rs.getString("created_by"), rs.getLong("version"),
                rs.getObject("verified_alarm_version", Long.class), time(rs, "verified_at"), time(rs, "closed_at"),
                time(rs, "created_at"), time(rs, "updated_at"), decode(rs.getString("snapshot_json")));
    }

    private static Status status(String status) { return status == null ? null : Status.valueOf(status); }

    private String encode(AlarmEvent.Source source) {
        try { return json.writeValueAsString(source); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot write alarm snapshot", failure); }
    }

    private AlarmEvent.Source decode(String source) {
        try { return json.readValue(source, AlarmEvent.Source.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot read alarm snapshot", failure); }
    }

    private static Instant time(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
