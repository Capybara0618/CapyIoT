package io.capyiot.device.video;

import io.capyiot.contract.internal.FaultApi;
import java.util.Set;

public interface HealthQueryPort { FaultApi.HealthResult query(Set<String> deviceIds); }
