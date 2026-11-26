package io.capyiot.device.video;

import java.util.List;

/** Only the asset module may implement these checks; platform never reads its repositories. */
public interface AssetScopePort {
    boolean parkExists(String parkId);
    boolean areasBelongToPark(String parkId, List<String> areaIds);
    default boolean auditVisible(AuthorizationContext actor,String parkId,List<String> deviceIds,List<String> areaIds) { return false; }
}
