package io.capyiot.device;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class DeviceNotifications {
    private record Change(String deviceId, String traceId, boolean stateChanged) {}
    private static class Subscription {
        final String campusId;
        final Instant expiresAt;
        final SseEmitter emitter;
        long lastSent = System.currentTimeMillis();
        Subscription(String campusId, Instant expiresAt, SseEmitter emitter) {
            this.campusId = campusId; this.expiresAt = expiresAt; this.emitter = emitter;
        }
    }
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Change> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> scenarios = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(64);

    SseEmitter subscribe(String campusId, Instant expiresAt) throws IOException {
        if (!slots.tryAcquire()) throw new DeviceProblem(HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT", "实时连接数已达上限，请稍后重试");
        String id = UUID.randomUUID().toString();
        var emitter = new SseEmitter(3600000L);
        subscriptions.put(id, new Subscription(campusId, expiresAt, emitter));
        Runnable remove = () -> { if (subscriptions.remove(id) != null) slots.release(); };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try { emitter.send(SseEmitter.event().name("connected").data(Map.of("campusId", campusId))); }
        catch (IOException error) { remove.run(); throw error; }
        return emitter;
    }

    void changed(String campusId, String deviceId, String traceId, boolean stateChanged) {
        pending.merge(campusId, new Change(deviceId, traceId, stateChanged),
                (previous, next) -> new Change(next.deviceId(), next.traceId(), previous.stateChanged() || next.stateChanged()));
    }

    @TransactionalEventListener
    public void scenarioChanged(ScenarioRuns.Changed change) {
        scenarios.put(change.campusId(), change.runId());
    }

    @Scheduled(fixedDelay = 250)
    void flush() {
        var changes = new ConcurrentHashMap<String, Change>();
        pending.forEach((campus, change) -> { if (pending.remove(campus, change)) changes.put(campus, change); });
        var runChanges = new ConcurrentHashMap<String, String>();
        scenarios.forEach((campus, id) -> { if (scenarios.remove(campus, id)) runChanges.put(campus, id); });
        subscriptions.forEach((id, subscription) -> {
            if (!Instant.now().isBefore(subscription.expiresAt)) { subscription.emitter.complete(); return; }
            try {
                Change change = changes.get(subscription.campusId);
                if (change != null) {
                    var data = Map.of("campusId", subscription.campusId, "deviceId", change.deviceId(), "traceId", change.traceId());
                    subscription.emitter.send(SseEmitter.event().name("events.changed").data(data));
                    if (change.stateChanged()) subscription.emitter.send(SseEmitter.event().name("device.state.changed").data(data));
                    subscription.lastSent = System.currentTimeMillis();
                }
                String runId = runChanges.get(subscription.campusId);
                if (runId != null) {
                    subscription.emitter.send(SseEmitter.event().name("scenario.updated").data(Map.of("scenarioRunId", runId)));
                    subscription.lastSent = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - subscription.lastSent > 15000) {
                    subscription.emitter.send(SseEmitter.event().comment("keepalive"));
                    subscription.lastSent = System.currentTimeMillis();
                }
            } catch (IOException | IllegalStateException error) {
                subscription.emitter.completeWithError(error);
                if (subscriptions.remove(id) != null) slots.release();
            }
        });
    }
}
