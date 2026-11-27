package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.util.*;

final class WorkPersonnel implements UserAssignmentPort {
  private final InternalHttpClient client;

  WorkPersonnel(InternalHttpClient client) {
    this.client = client;
  }

  private List<DeviceApi.Resource> resources(Collection<DeviceQueryPort.DeviceRef> devices) {
    WorkDevices.outside();
    var ids = devices.stream().map(DeviceQueryPort.DeviceRef::deviceId).toList();
    var result =
        client.call(
            InternalEndpoint.DEVICE_REFERENCES,
            null,
            new DeviceApi.Batch(ids),
            DeviceApi.ScopeResult.class,
            RequestCalls.budget(),
            RequestCalls.correlation());
    if (result.devices().size() != ids.size()
        || !new HashSet<>(ids)
            .equals(
                result.devices().stream()
                    .map(DeviceApi.Resource::deviceId)
                    .collect(java.util.stream.Collectors.toSet())))
      throw ServiceFailure.unavailable("DEVICE_RESOURCE_INCOMPLETE", ServiceId.DEVICE, false);
    return result.devices();
  }

  private PlatformApi.EligibilityResult query(PlatformApi.EligibilityRequest request) {
    WorkDevices.outside();
    return client.call(
        InternalEndpoint.USER_ELIGIBILITY,
        RequestCalls.actor(),
        request,
        PlatformApi.EligibilityResult.class,
        RequestCalls.budget(),
        RequestCalls.correlation());
  }

  public void requireRoleForDevices(
      String user, String role, Collection<DeviceQueryPort.DeviceRef> devices) {
    var result =
        query(
            new PlatformApi.EligibilityRequest(
                List.of(user), PlatformApi.PersonnelRole.valueOf(role), resources(devices)));
    if (result.nextCursor() != null
        || result.candidates().size() != 1
        || !result.candidates().getFirst().userId().equals(user)
        || !result.candidates().getFirst().eligible()) throw ApiProblem.invalid("责任人员缺少履职资格");
  }

  List<Map<String, Object>> candidates(List<DeviceQueryPort.DeviceRef> devices) {
    var snapshots = resources(devices);
    var result = new ArrayList<Map<String, Object>>();
    for (var role : PlatformApi.PersonnelRole.values()) {
      String cursor = null;
      var seen = new HashSet<String>();
      int pages = 0;
      do {
        if (++pages > 20 || result.size() > 1000)
          throw ServiceFailure.unavailable("CANDIDATE_QUERY_LIMIT", ServiceId.PLATFORM, false);
        var page = query(new PlatformApi.EligibilityRequest(List.of(), role, snapshots, cursor));
        for (var c : page.candidates()) {
          if (c.role() != role)
            throw ServiceFailure.unavailable(
                "CANDIDATE_INVALID_RESPONSE", ServiceId.PLATFORM, false);
          if (c.eligible())
            result.add(
                Map.of(
                    "userId",
                    c.userId(),
                    "displayName",
                    c.displayName(),
                    "roleCode",
                    c.role().name()));
        }
        cursor = page.nextCursor();
        if (cursor != null && !seen.add(cursor))
          throw ServiceFailure.unavailable("CANDIDATE_CURSOR_INVALID", ServiceId.PLATFORM, false);
      } while (cursor != null);
    }
    if (result.size() > 1000)
      throw ServiceFailure.unavailable("CANDIDATE_QUERY_LIMIT", ServiceId.PLATFORM, false);
    return List.copyOf(result);
  }
}
