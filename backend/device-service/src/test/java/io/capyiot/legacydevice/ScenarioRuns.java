package io.capyiot.legacydevice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ScenarioRuns {
    enum Template {
        NETWORK_UPSTREAM_FAILURE(45, 55), DEVICE_FLAPPING(90, 90), SENSOR_THRESHOLD_BREACH(15, 25);

        final int minimumSeconds;
        final int defaultSeconds;

        Template(int minimumSeconds, int defaultSeconds) {
            this.minimumSeconds = minimumSeconds;
            this.defaultSeconds = defaultSeconds;
        }
    }

    enum Phase {
        WARMING, OBSERVING, REBOUNDING, OBSERVING_AGAIN, RECOVERING,
        RECOVERY_REQUIRED, RECOVERED, STOPPED, FAILED
    }

    enum EndMode { STOP, RECOVER, FAILURE }

    record Start(@NotNull Template template, @NotBlank @Size(max = 64) String deviceId,
            @NotNull Integer durationSeconds, Integer temperature) {}
    record Run(String id, String campusId, Template template, String operator, List<String> targetIds,
            int durationSeconds, Integer temperature, Phase phase, EndMode endMode, Instant startedAt,
            Instant phaseAt, Instant observedAt, Instant endedAt) {}
    record Log(long id, Instant at, String message) {}
    record Changed(String campusId, String runId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ScenarioDevices devices;
    private final ApplicationEventPublisher events;

    ScenarioRuns(JdbcTemplate jdbc, ObjectMapper json, ScenarioDevices devices, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.devices = devices;
        this.events = events;
    }

    @Transactional
    public Run start(String campus, String operator, Start request) {
        if (request.durationSeconds() < request.template().minimumSeconds || request.durationSeconds() > 300)
            throw DeviceProblem.badRequest("持续时间须在 " + request.template().minimumSeconds + "–300 秒之间");
        if (request.template() == Template.SENSOR_THRESHOLD_BREACH
                && (request.temperature() == null || request.temperature() < 35 || request.temperature() > 80))
            throw DeviceProblem.badRequest("模拟温度须在 35–80 °C 之间");
        if (request.template() != Template.SENSOR_THRESHOLD_BREACH && request.temperature() != null)
            throw DeviceProblem.badRequest("只有温度越限场景接受温度参数");
        jdbc.queryForObject("SELECT id FROM campus WHERE id=? FOR UPDATE", String.class, campus);
        if (!jdbc.queryForList("SELECT id FROM scenario_run WHERE campus_id=? AND ended_at IS NULL", String.class, campus).isEmpty())
            throw DeviceProblem.conflict("园区已有场景运行，请先完成恢复");
        var targets = devices.targets(campus, request.template(), request.deviceId());
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO scenario_run(id,campus_id,template,operator_name,target_ids,duration_seconds,temperature,phase,started_at,phase_at)
                VALUES (?,?,?,?,?,?,?,'WARMING',?,?)
                """, id, campus, request.template().name(), operator, writeTargets(targets),
                request.durationSeconds(), request.temperature(), Timestamp.from(now), Timestamp.from(now));
        Run run = find(campus, id);
        log(run, operator + " 启动模拟，等待 MQTT 心跳完成预热");
        return run;
    }

    List<Run> recent(String campus) {
        return jdbc.query("SELECT * FROM scenario_run WHERE campus_id=? ORDER BY ended_at IS NULL DESC,started_at DESC LIMIT 12",
                this::read, campus);
    }

    List<Run> active() {
        return jdbc.query("""
                SELECT * FROM scenario_run WHERE ended_at IS NULL ORDER BY started_at
                """, this::read);
    }

    Run find(String campus, String id) {
        return find(campus, id, false);
    }

    private Run find(String campus, String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM scenario_run WHERE campus_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                this::read, campus, id);
        if (rows.isEmpty()) throw DeviceProblem.notFound("场景运行不存在");
        return rows.getFirst();
    }

    List<Log> logs(String id) {
        return jdbc.query("SELECT * FROM scenario_run_log WHERE run_id=? ORDER BY id DESC LIMIT 50",
                (rs, row) -> new Log(rs.getLong("id"), DeviceQueries.instant(rs, "logged_at"), rs.getString("message")), id)
                .reversed();
    }

    @Transactional
    public Run requestEnd(String campus, String id, String operator, EndMode mode) {
        Run run = find(campus, id, true);
        if (run.endedAt() != null || run.phase() == Phase.RECOVERING)
            return run;
        if (run.phase() == Phase.RECOVERY_REQUIRED && mode == EndMode.STOP)
            throw DeviceProblem.conflict("请检查设备凭据和服务连接，然后重试恢复");
        jdbc.update("UPDATE scenario_run SET end_mode=? WHERE id=?", mode.name(), id);
        changePhase(id, Phase.RECOVERING, Instant.now(), false);
        log(run, operator + (mode == EndMode.STOP ? " 请求停止注入并恢复设备" : " 请求立即恢复设备"));
        return find(campus, id);
    }

    @Transactional
    public void advance(Run expected, Phase next, String message) {
        Run run = find(expected.campusId(), expected.id(), true);
        if (run.phase() != expected.phase() || !run.phaseAt().equals(expected.phaseAt())) return;
        if (next == Phase.RECOVERED && run.endMode() == EndMode.STOP) next = Phase.STOPPED;
        if (next == Phase.RECOVERED && run.endMode() == EndMode.FAILURE) next = Phase.FAILED;
        Instant now = Instant.now();
        boolean terminal = next == Phase.RECOVERED || next == Phase.STOPPED || next == Phase.FAILED;
        changePhase(run.id(), next, now, terminal);
        log(run, next == Phase.STOPPED ? "已停止，设备恢复上报" :
                next == Phase.FAILED ? "注入未完成，设备已恢复上报" : message);
    }

    @Transactional
    public void observe(Run expected, String message) {
        int updated = jdbc.update("""
                UPDATE scenario_run SET observed_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND phase=? AND phase_at=? AND observed_at IS NULL
                """, expected.id(), expected.phase().name(), Timestamp.from(expected.phaseAt()));
        if (updated == 1) log(expected, message);
    }

    @Transactional
    public void failInjection(Run expected, String message) {
        Run run = find(expected.campusId(), expected.id(), true);
        if (run.phase() != expected.phase() || !run.phaseAt().equals(expected.phaseAt())) return;
        jdbc.update("UPDATE scenario_run SET end_mode='FAILURE' WHERE id=?", run.id());
        changePhase(run.id(), Phase.RECOVERING, Instant.now(), false);
        log(run, message);
    }

    private void changePhase(String id, Phase phase, Instant at, boolean terminal) {
        Timestamp timestamp = Timestamp.from(at);
        jdbc.update("UPDATE scenario_run SET phase=?,phase_at=?,observed_at=NULL,ended_at=? WHERE id=?",
                phase.name(), timestamp, terminal ? timestamp : null, id);
    }

    private void log(Run run, String message) {
        jdbc.update("INSERT INTO scenario_run_log(run_id,message) VALUES (?,?)", run.id(), message);
        events.publishEvent(new Changed(run.campusId(), run.id()));
    }

    private Run read(ResultSet rs, int row) throws SQLException {
        String endMode = rs.getString("end_mode");
        return new Run(rs.getString("id"), rs.getString("campus_id"), Template.valueOf(rs.getString("template")),
                rs.getString("operator_name"), readTargets(rs.getString("target_ids")), rs.getInt("duration_seconds"),
                rs.getObject("temperature", Integer.class), Phase.valueOf(rs.getString("phase")),
                endMode == null ? null : EndMode.valueOf(endMode), DeviceQueries.instant(rs, "started_at"),
                DeviceQueries.instant(rs, "phase_at"), DeviceQueries.instant(rs, "observed_at"), DeviceQueries.instant(rs, "ended_at"));
    }

    private List<String> readTargets(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored scenario targets", error);
        }
    }

    private String writeTargets(List<String> targets) {
        try {
            return json.writeValueAsString(targets);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize scenario targets", error);
        }
    }

    static DeviceProblem unavailable(String message) {
        return new DeviceProblem(HttpStatus.SERVICE_UNAVAILABLE, "SCENARIO_UNAVAILABLE", message);
    }
}
