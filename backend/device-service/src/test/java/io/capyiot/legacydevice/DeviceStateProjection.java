package io.capyiot.legacydevice;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class DeviceStateProjection {
    private final StringRedisTemplate redis;
    private final long timeoutMillis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    DeviceStateProjection(StringRedisTemplate redis, @Value("${capyiot.offline-timeout-ms:30000}") long timeoutMillis) {
        this.redis = redis;
        this.timeoutMillis = timeoutMillis;
        script.setLocation(new ClassPathResource("redis/apply-device-state.lua"));
        script.setResultType(Long.class);
    }

    void apply(DeviceStateTransactions.Snapshot state) {
        long heartbeat = state.heartbeat() == null ? 0 : state.heartbeat().toEpochMilli();
        redis.execute(script, List.of("device:state:" + state.deviceId(), "device:offline:deadline"),
                state.connectivity(), state.health(), Long.toString(state.sessionEpoch()), Long.toString(state.seq()),
                Long.toString(heartbeat), Long.toString(heartbeat + timeoutMillis), Long.toString(state.version()), state.deviceId());
    }
}
