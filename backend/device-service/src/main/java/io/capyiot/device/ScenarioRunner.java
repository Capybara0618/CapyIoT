package io.capyiot.device;

import static io.capyiot.contract.DeviceMessage.Type.*;
import static io.capyiot.device.ScenarioRuns.Phase.*;
import static io.capyiot.device.ScenarioRuns.Template.*;

import io.capyiot.contract.DeviceMessage;
import io.capyiot.device.ScenarioRuns.Run;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="capyiot.legacy-scenarios-enabled",havingValue="true",matchIfMissing=true)
class ScenarioRunner {
    private record Signal(String deviceId, DeviceMessage.Type type) {}
    private record PhaseAttempt(String runId, Instant phaseAt, Instant firstTick) {}
    private static final Duration LEASE = Duration.ofSeconds(75);

    private final ScenarioRuns runs;
    private final ScenarioDevices devices;
    private final DeviceStateTransactions states;
    private final JdbcTemplate jdbc;
    private final LockProvider locks;
    private SimpleLock leadership;
    private PhaseAttempt attempt;

    ScenarioRunner(ScenarioRuns runs, ScenarioDevices devices, DeviceStateTransactions states,
            JdbcTemplate jdbc, LockProvider locks) {
        this.runs = runs;
        this.devices = devices;
        this.states = states;
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Scheduled(fixedDelay = 1000)
    synchronized void tick() {
        if (!devices.enabled()) return;
        // Keep the same publisher and its device sessions across ticks while this instance owns the lease.
        if (leadership == null) {
            leadership = locks.lock(new LockConfiguration(Instant.now(), "demo-scenario-runner", LEASE, Duration.ZERO))
                    .orElse(null);
            if (leadership == null) return;
            attempt = null;
            devices.clearChannels();
        } else {
            leadership = leadership.extend(LEASE, Duration.ZERO).orElse(null);
            if (leadership == null) return;
        }
        var active = runs.active();
        Map<String, ScenarioDevices.Credential> credentials;
        try {
            credentials = devices.credentials();
        } catch (DeviceProblem configurationError) {
            credentials = Map.of();
        }
        var run = active.stream().filter(item -> item.phase() != RECOVERY_REQUIRED).findFirst();
        if (run.isPresent()) step(run.get(), credentials);
        devices.keepAlive(credentials, active.stream().flatMap(item -> item.targetIds().stream()).toList());
    }

    @PreDestroy
    synchronized void release() {
        if (leadership != null) leadership.unlock();
        leadership = null;
    }

    private void step(Run run, Map<String, ScenarioDevices.Credential> credentials) {
        Set<Signal> received = processedSignals(run);
        Instant now = Instant.now();
        if (attempt == null || !attempt.runId().equals(run.id()) || !attempt.phaseAt().equals(run.phaseAt()))
            attempt = new PhaseAttempt(run.id(), run.phaseAt(), now);
        long elapsed = Duration.between(run.phaseAt(), now).toSeconds();
        long triedFor = Duration.between(attempt.firstTick(), now).toSeconds();
        try {
            switch (run.phase()) {
                case WARMING -> {
                    sendHeartbeats(run, credentials);
                    if (online(run, received)) {
                        runs.advance(run, OBSERVING, injectionMessage(run));
                    } else if (triedFor >= 25) {
                        runs.failInjection(run, "未观察到全部设备的有效预热心跳，正在恢复");
                    }
                }
                case OBSERVING, OBSERVING_AGAIN -> observe(run, credentials, received, elapsed, triedFor);
                case REBOUNDING -> {
                    sendHeartbeats(run, credentials);
                    if (online(run, received)) {
                        runs.advance(run, OBSERVING_AGAIN, "已观察到设备重新上线，开始第二次暂停心跳");
                    } else if (triedFor >= 25) {
                        runs.failInjection(run, "抖动场景未观察到设备重新上线，正在恢复");
                    }
                }
                case RECOVERING -> recover(run, credentials, received, triedFor);
                default -> { }
            }
        } catch (DeviceProblem | IOException | MqttException error) {
            if (run.phase() == RECOVERING && triedFor >= 35) {
                runs.advance(run, RECOVERY_REQUIRED,
                        "尚未确认设备恢复，请检查设备凭据、上报会话和 MQTT 连接后重试");
            } else if (run.phase() != RECOVERING && triedFor >= 25) {
                runs.failInjection(run, "模拟消息未能持续发送，请检查设备凭据、上报会话和 MQTT 连接");
            }
        }
    }

    private void observe(Run run, Map<String, ScenarioDevices.Credential> credentials,
            Set<Signal> received, long elapsed, long triedFor) throws IOException, MqttException {
        String root = run.targetIds().getFirst();
        boolean observed;
        String message;
        int duration = run.durationSeconds();
        switch (run.template()) {
            case NETWORK_UPSTREAM_FAILURE -> {
                sendUntilProcessed(run, credentials, received, root, DEVICE_FAULT, null);
                devices.send(run, credentials.get(root), HEARTBEAT, null);
                observed = received.contains(new Signal(root, DEVICE_FAULT))
                        && states.snapshot(root).health().equals("ABNORMAL")
                        && offline(run.targetIds().subList(1, run.targetIds().size()));
                message = "已观察到交换机故障及下游心跳超时";
            }
            case SENSOR_THRESHOLD_BREACH -> {
                sendUntilProcessed(run, credentials, received, root, SENSOR_READING, run.temperature());
                devices.send(run, credentials.get(root), HEARTBEAT, null);
                var state = states.snapshot(root);
                observed = received.contains(new Signal(root, SENSOR_READING))
                        && state.connectivity().equals("ONLINE") && state.health().equals("ABNORMAL");
                message = "已处理温度越限事件，传感器仍在线";
            }
            case DEVICE_FLAPPING -> {
                observed = offline(run.targetIds());
                message = run.phase() == OBSERVING ? "已观察到第一次离线" : "已观察到第二次离线";
                int first = duration / 2;
                duration = run.phase() == OBSERVING ? first : duration - first;
            }
            default -> throw new IllegalStateException("Unsupported scenario");
        }
        if (observed && run.observedAt() == null) runs.observe(run, message);
        if (observed && elapsed >= duration) {
            if (run.template() == DEVICE_FLAPPING && run.phase() == OBSERVING) {
                runs.advance(run, REBOUNDING, "首次离线阶段结束，恢复心跳以验证抖动");
            } else {
                runs.advance(run, RECOVERING, "设定时长已到，开始发送恢复事件和心跳");
            }
        } else if (elapsed >= duration + 20L && triedFor >= 20) {
            runs.failInjection(run, "等待预期设备状态超时，正在恢复已注入的故障");
        }
    }

    private void recover(Run run, Map<String, ScenarioDevices.Credential> credentials,
            Set<Signal> received, long triedFor) throws IOException, MqttException {
        String root = run.targetIds().getFirst();
        try {
            if (run.template() == SENSOR_THRESHOLD_BREACH)
                sendUntilProcessed(run, credentials, received, root, SENSOR_READING, 25);
            if (run.template() != DEVICE_FLAPPING)
                sendUntilProcessed(run, credentials, received, root, DEVICE_RECOVERED, null);
        } finally {
            sendHeartbeats(run, credentials);
        }
        boolean diagnostics = switch (run.template()) {
            case NETWORK_UPSTREAM_FAILURE -> received.contains(new Signal(root, DEVICE_RECOVERED));
            case SENSOR_THRESHOLD_BREACH -> received.contains(new Signal(root, DEVICE_RECOVERED))
                    && received.contains(new Signal(root, SENSOR_READING));
            case DEVICE_FLAPPING -> true;
        };
        if (diagnostics && online(run, received) && (run.template() == DEVICE_FLAPPING
                || states.snapshot(root).health().equals("NORMAL"))) {
            runs.advance(run, RECOVERED, "恢复事件已处理，已观察到目标设备恢复上报");
        } else if (triedFor >= 35) {
            runs.advance(run, RECOVERY_REQUIRED, "尚未观察到全部设备恢复，请检查事件中心后重试恢复");
        }
    }

    private Set<Signal> processedSignals(Run run) {
        return new HashSet<>(jdbc.query("""
                SELECT DISTINCT device_id,event_type FROM device_event
                WHERE campus_id=? AND scenario_run_id=? AND processing_status='PROCESSED'
                    AND received_at>=? AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.scenarioPhase'))=?
                """, (rs, row) -> new Signal(rs.getString(1), DeviceMessage.Type.valueOf(rs.getString(2))),
                run.campusId(), run.id(), Timestamp.from(run.phaseAt()), run.phase().name()));
    }

    private boolean online(Run run, Set<Signal> received) {
        return run.targetIds().stream().allMatch(id -> received.contains(new Signal(id, HEARTBEAT))
                && states.snapshot(id).connectivity().equals("ONLINE"));
    }

    private boolean offline(List<String> ids) {
        return ids.stream().allMatch(id -> states.snapshot(id).connectivity().equals("OFFLINE"));
    }

    private void sendHeartbeats(Run run, Map<String, ScenarioDevices.Credential> credentials)
            throws IOException, MqttException {
        RuntimeException unavailable = null;
        for (String id : run.targetIds()) {
            try {
                devices.send(run, credentials.get(id), HEARTBEAT, null);
            } catch (DeviceProblem error) {
                unavailable = error;
            }
        }
        if (unavailable != null) throw unavailable;
    }

    private void sendUntilProcessed(Run run, Map<String, ScenarioDevices.Credential> credentials,
            Set<Signal> received, String id, DeviceMessage.Type type, Integer temperature)
            throws IOException, MqttException {
        if (!received.contains(new Signal(id, type)))
            devices.send(run, credentials.get(id), type, temperature);
    }

    private static String injectionMessage(Run run) {
        return switch (run.template()) {
            case NETWORK_UPSTREAM_FAILURE -> "预热完成，发送交换机故障，暂停下游心跳并等待离线巡检";
            case DEVICE_FLAPPING -> "预热完成，开始第一次暂停心跳";
            case SENSOR_THRESHOLD_BREACH -> "预热完成，发送 " + run.temperature() + " °C 温度读数并保留心跳";
        };
    }
}
