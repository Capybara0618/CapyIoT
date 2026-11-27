package io.capyiot.alarm.fault;

import java.util.Map;
import java.util.Set;

public interface HealthQueryPort {
  Map<String, String> deviceHealth(Set<String> deviceIds);
}
