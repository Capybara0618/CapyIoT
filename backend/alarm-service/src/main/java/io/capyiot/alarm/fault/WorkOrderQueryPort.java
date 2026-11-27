package io.capyiot.alarm.fault;

import java.util.*;

public interface WorkOrderQueryPort {
  record OrderRef(
      long workOrderId, String workOrderNo, String incidentId, String status, long version) {}

  List<OrderRef> forIncident(String id);
}
