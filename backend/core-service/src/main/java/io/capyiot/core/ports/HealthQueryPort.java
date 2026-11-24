package io.capyiot.core.ports;

import java.util.Map;
import java.util.Set;

public interface HealthQueryPort {
    Map<String,String> deviceHealth(Set<String> deviceIds);
}
