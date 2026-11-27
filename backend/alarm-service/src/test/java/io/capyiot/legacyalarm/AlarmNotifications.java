package io.capyiot.legacyalarm;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class AlarmNotifications {
    private static final class Subscription {
        final String campus;
        final Instant expires;
        long revision;
        Instant lastFrame = Instant.now();
        Subscription(String campus, Instant expires, long revision) {
            this.campus = campus;
            this.expires = expires;
            this.revision = revision;
        }
    }

    private final AlarmQueries queries;
    private final Semaphore slots = new Semaphore(64);
    private final ConcurrentHashMap<SseEmitter, Subscription> subscriptions = new ConcurrentHashMap<>();

    AlarmNotifications(AlarmQueries queries) { this.queries = queries; }

    SseEmitter open(String campus, Instant expires) throws IOException {
        long revision = queries.revision(campus);
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "实时连接已满");
        var emitter = new SseEmitter(3600000L);
        var subscription = new Subscription(campus, expires, revision);
        subscriptions.put(emitter, subscription);
        Runnable close = () -> { if (subscriptions.remove(emitter) != null) slots.release(); };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("campusId", campus, "version", revision)));
        } catch (IOException | IllegalStateException error) {
            close.run();
            throw error;
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    void notifyChanges() {
        Instant now = Instant.now();
        var revisions = new HashMap<String, Long>();
        subscriptions.forEach((emitter, subscription) -> {
            if (!now.isBefore(subscription.expires)) {
                emitter.complete();
                return;
            }
            long next = revisions.computeIfAbsent(subscription.campus, queries::revision);
            try {
                if (next != subscription.revision) {
                    emitter.send(SseEmitter.event().name("alarm.updated").id(Long.toString(next))
                            .data(Map.of("campusId", subscription.campus, "version", next)));
                    subscription.revision = next;
                    subscription.lastFrame = now;
                } else if (subscription.lastFrame.plusSeconds(15).isBefore(now)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    subscription.lastFrame = now;
                }
            } catch (IOException | IllegalStateException error) {
                if (subscriptions.remove(emitter) != null) slots.release();
                emitter.completeWithError(error);
            }
        });
    }
}
