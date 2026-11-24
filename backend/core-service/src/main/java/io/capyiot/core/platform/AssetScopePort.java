package io.capyiot.core.platform;

import java.util.List;

/** Only the asset module may implement these checks; platform never reads its repositories. */
public interface AssetScopePort {
    boolean parkExists(String parkId);
    boolean areasBelongToPark(String parkId, List<String> areaIds);
}
