package io.capyiot.core.ports;

import java.util.*;

public interface WorkOrderQueryPort {
    record OrderRef(long workOrderId,String workOrderNo,String incidentId,String status,long version) {}
    List<OrderRef> forIncident(String id);
    Optional<OrderRef> forReservation(String reservationId);
}
