package io.capyiot.alarm.fault;

public interface IncidentLookup {
  record IncidentRef(
      String incidentId, String parkId, String status, String priority, long version) {}

  IncidentRef requireIncident(AuthorizationContext actor, String permission, String id);
}
