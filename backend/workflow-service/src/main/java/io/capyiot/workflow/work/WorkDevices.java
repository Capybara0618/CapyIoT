package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.util.*;

final class WorkDevices implements DeviceQueryPort {
  private final InternalHttpClient client;

  WorkDevices(InternalHttpClient client) {
    this.client = client;
  }

  private <T> T query(InternalEndpoint endpoint, Object body, Class<T> type, String... ids) {
    outside();
    return client.call(
        endpoint, null, body, type, RequestCalls.budget(), RequestCalls.correlation(), ids);
  }

  static void outside() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("No service calls inside work-order transactions");
  }

  public DeviceRef device(String id) {
    var refs =
        query(
                InternalEndpoint.DEVICE_REFERENCES,
                new DeviceApi.Batch(List.of(id)),
                DeviceApi.ScopeResult.class)
            .devices();
    if (refs.size() != 1 || !refs.getFirst().deviceId().equals(id)) throw unavailable();
    return ref(refs.getFirst());
  }

  public List<DeviceRef> requireVisibleDevices(
      AuthorizationContext actor, String permission, Collection<String> ids) {
    outside();
    var all = List.copyOf(ids);
    var result = new ArrayList<DeviceRef>();
    for (int start = 0; start < all.size(); start += 100) {
      var batch = all.subList(start, Math.min(start + 100, all.size()));
      var response =
          client.call(
              InternalEndpoint.DEVICE_SCOPE,
              RequestCalls.actor(),
              new DeviceApi.ScopeRequest(permission, batch),
              DeviceApi.ScopeResult.class,
              RequestCalls.budget(),
              RequestCalls.correlation());
      if (response.devices().size() != batch.size()
          || !new HashSet<>(batch)
              .equals(
                  response.devices().stream()
                      .map(DeviceApi.Resource::deviceId)
                      .collect(java.util.stream.Collectors.toSet()))) throw unavailable();
      response.devices().forEach(r -> result.add(ref(r)));
    }
    return List.copyOf(result);
  }

  public Set<String> visibleDeviceIds(AuthorizationContext actor, String permission) {
    outside();
    String cursor = null;
    var result = new HashSet<String>();
    int pages = 0;
    do {
      if (++pages > 100) throw unavailable();
      var page =
          client.call(
              InternalEndpoint.DEVICE_VISIBLE_PAGE,
              RequestCalls.actor(),
              new DeviceApi.VisiblePageRequest(permission, cursor),
              DeviceApi.VisiblePage.class,
              RequestCalls.budget(),
              RequestCalls.correlation());
      if (page.nextCursor() != null && cursor != null && page.nextCursor().compareTo(cursor) <= 0)
        throw unavailable();
      result.addAll(page.deviceIds());
      cursor = page.nextCursor();
    } while (cursor != null);
    return Set.copyOf(result);
  }

  public List<Map<String, Object>> networkRelations(String park, java.time.Instant at) {
    throw new UnsupportedOperationException("Work orders do not query historical network topology");
  }

  boolean evidence(String id, List<String> targets) {
    outside();
    var result =
        client.call(
            InternalEndpoint.DEVICE_EVIDENCE,
            RequestCalls.actor(),
            new FaultApi.EvidenceRequest(id, targets),
            FaultApi.EvidenceResult.class,
            RequestCalls.budget(),
            RequestCalls.correlation());
    return result.visible() && result.related();
  }

  private DeviceRef ref(DeviceApi.Resource r) {
    return new DeviceRef(
        r.deviceId(),
        r.parkId(),
        r.areaId(),
        r.assetCode(),
        r.deviceType(),
        r.name(),
        r.assetStatus());
  }

  private ServiceFailure unavailable() {
    return ServiceFailure.unavailable("DEVICE_FACTS_INCOMPLETE", ServiceId.DEVICE, false);
  }
}
