package io.capyiot.alarm.fault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Temporary Core/Gateway bridge, explicitly enabled and time bounded. Remove in MS-08. */
@Component
final class LegacyWindow {
  private final boolean enabled;
  private final Instant expires;
  private final byte[] key;

  LegacyWindow(
      @Value("${capyiot.faults.legacy.enabled:false}") boolean enabled,
      @Value("${capyiot.faults.legacy.expires-at:1970-01-01T00:00:00Z}") Instant expires,
      @Value("${capyiot.faults.legacy.service-key:}") String key) {
    if (enabled && (key.length() < 32 || !expires.isAfter(Instant.now())))
      throw new IllegalArgumentException(
          "A valid expiring legacy bridge configuration is required");
    this.enabled = enabled;
    this.expires = expires;
    this.key = key.getBytes(StandardCharsets.UTF_8);
  }

  boolean active() {
    return enabled && Instant.now().isBefore(expires);
  }

  void verify(String provided) {
    if (!active()
        || provided == null
        || !MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8)))
      throw new ApiProblem(401, "SERVICE_UNAUTHORIZED", "迁移入口未启用、已过期或身份无效");
  }
}
