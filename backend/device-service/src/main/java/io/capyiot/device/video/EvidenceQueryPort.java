package io.capyiot.device.video;

import java.util.Collection;

/** Evidence is resolved by its owning context, never by another context's repository. */
public interface EvidenceQueryPort {
    boolean visibleEvidence(AuthorizationContext actor,String referenceId,Collection<String> workOrderDeviceIds);
}
