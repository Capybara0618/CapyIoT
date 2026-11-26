package io.capyiot.platform;

import io.capyiot.contract.internal.DeviceApi;
import java.util.List;

/** Resource authority is remote; identity lookup never calls this port. */
public interface AssetScopeClient {
    DeviceApi.ResourceValidity validate(String parkId,List<String> areaIds);
    boolean auditVisible(AuthorizationContext actor,String parkId,List<String> deviceIds,List<String> areaIds);
}
