package io.capyiot.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
class DeviceSummaries {
    private final DeviceQueries queries;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Counter hits;
    private final Counter misses;

    DeviceSummaries(DeviceQueries queries, StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters,
            @Value("${capyiot.query-cache-enabled:true}") boolean enabled) {
        this.queries = queries;
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        hits = meters.counter("capyiot.query.cache", "result", "hit");
        misses = meters.counter("capyiot.query.cache", "result", "miss");
    }

    DeviceModels.Summary summary(String campus) {
        if (!enabled) return queries.summary(campus);
        String key = "capyiot:devices:summary:" + campus;
        try {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                var summary = json.readValue(value, DeviceModels.Summary.class);
                if (summary != null) {
                    hits.increment();
                    return summary;
                }
            }
        } catch (DataAccessException | JsonProcessingException unavailable) {
            misses.increment();
            return queries.summary(campus);
        }
        misses.increment();
        var result = queries.summary(campus);
        try {
            redis.opsForValue().setIfAbsent(key, json.writeValueAsString(result), Duration.ofSeconds(2));
        } catch (DataAccessException | JsonProcessingException unavailable) {
            // MySQL is the authoritative fallback.
        }
        return result;
    }
}
