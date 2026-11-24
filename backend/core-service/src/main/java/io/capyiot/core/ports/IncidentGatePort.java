package io.capyiot.core.ports;

import io.capyiot.core.platform.AuthorizationContext;
import java.util.*;

public interface IncidentGatePort {
    record IncidentRef(String incidentId,String parkId,String status,String priority,long version) {}
    record Reservation(String reservationId,String incidentId,String status,Long workOrderId) {}
    IncidentRef requireIncident(AuthorizationContext actor,String permission,String id);
    Reservation reserve(AuthorizationContext actor,String incidentId,String operation,String key,String requestId,String requestHash);
    void commit(String reservationId,long workOrderId);
    void releaseRolledBack(String reservationId,String reason);
    void markUncertain(String reservationId);
}
