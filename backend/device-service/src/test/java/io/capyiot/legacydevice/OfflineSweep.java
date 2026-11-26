package io.capyiot.legacydevice;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capyiot.sweep-enabled", matchIfMissing = true)
class OfflineSweep {
    private static final String DEADLINES = "device:offline:deadline";
    private static final int BATCH_SIZE = 100;
    private final DefaultRedisScript<Long> eligible = new DefaultRedisScript<>();
    private final LockProvider locks;
    private final StringRedisTemplate redis;
    private final DeviceStateTransactions transactions;
    private final DeviceStateProjection projection;
    private final DeviceNotifications notifications;
    private final long timeoutMillis;
    private String after = "";

    OfflineSweep(LockProvider locks, StringRedisTemplate redis, DeviceStateTransactions transactions,
            DeviceStateProjection projection, DeviceNotifications notifications,
            @Value("${capyiot.offline-timeout-ms:30000}") long timeoutMillis) {
        this.locks = locks;
        this.redis = redis;
        this.transactions = transactions;
        this.projection = projection;
        this.notifications = notifications;
        this.timeoutMillis = timeoutMillis;
        eligible.setLocation(new ClassPathResource("redis/read-offline-candidate.lua"));
        eligible.setResultType(Long.class);
    }

    @Scheduled(fixedRateString = "${capyiot.offline-sweep-ms:5000}",
            initialDelayString = "${capyiot.offline-sweep-ms:5000}")
    void run() {
        var lock = locks.lock(new LockConfiguration(Instant.now(), "device-offline-sweep",
                Duration.ofMinutes(1), Duration.ofSeconds(4)));
        if (lock.isEmpty()) return;
        try {
            sweep(Instant.now());
        } finally {
            lock.get().unlock();
        }
    }

    void sweep(Instant now) {
        var candidates = redis.opsForZSet().rangeByScore(DEADLINES, 0, now.toEpochMilli(), 0, BATCH_SIZE);
        if (candidates != null) for (String deviceId : candidates) {
            Long version = redis.execute(eligible, List.of("device:state:" + deviceId, DEADLINES),
                    deviceId, Long.toString(now.toEpochMilli()));
            if (version == null) continue;

            // MySQL commits the transition and outbox before the projection removes the deadline.
            // If Redis fails, the next pass repairs it from the committed snapshot.
            var result = transactions.expire(deviceId, version, now, timeoutMillis);
            if (result == null) continue;
            projection.apply(result.snapshot());
            notifications.changed(result.campusId(), deviceId, result.traceId(), result.changed());
        }

        // Read a bounded slice of durable online states to rebuild a lost or lagging Redis projection.
        var snapshots = transactions.onlineSnapshots(after, BATCH_SIZE);
        for (var snapshot : snapshots) projection.apply(snapshot);
        after = snapshots.size() == BATCH_SIZE ? snapshots.getLast().deviceId() : "";
    }

    @Configuration(proxyBeanMethods = false)
    static class LockConfigurationSupport {
        @Bean
        LockProvider offlineLockProvider(JdbcTemplate jdbc) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(jdbc).usingDbTime().build());
        }
    }
}
