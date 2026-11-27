package io.capyiot.core.ports;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.platform.AuthorizationContext;
import io.capyiot.service.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * MS-04 bridge only. No fault datasource, store, or automatic fallback; removed with Core in MS-08.
 */
@Component
@ConditionalOnProperty(
    name = "capyiot.platform.test-fixture",
    havingValue = "false",
    matchIfMissing = true)
public final class RemoteFaultGate implements IncidentGatePort, EvidenceQueryPort {
  private final URI origin;
  private final String key;
  private final ServiceTransport transport;
  private final ObjectMapper json;

  @org.springframework.beans.factory.annotation.Autowired
  public RemoteFaultGate(
      @Value("${capyiot.fault.url:https://alarm-service:8083}") URI origin,
      @Value("${capyiot.foundation.service-key}") String key,
      @Value("${capyiot.fault.tls-ca-file:/run/secrets/internal-ca.pem}") String ca,
      ObjectMapper json) {
    this(origin, key, new JdkServiceTransport(ServiceTls.trust(Path.of(ca))), json);
  }

  RemoteFaultGate(URI origin, String key, ServiceTransport transport, ObjectMapper json) {
    if (!"https".equals(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !Set.of("", "/").contains(origin.getPath())
        || key.length() < 32)
      throw new IllegalArgumentException("Fixed TLS fault authority required");
    this.origin = origin;
    this.key = key;
    this.transport = transport;
    this.json = json.copy();
  }

  @jakarta.annotation.PreDestroy
  public void close() throws Exception {
    if (transport instanceof AutoCloseable c) c.close();
  }

  private CallBudget budget() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      var r = a.getRequest();
      if (r.getAttribute("capyiot.call-budget") instanceof CallBudget b) return b;
      var b = CallBudget.start(Duration.ofSeconds(10), Clock.systemUTC());
      r.setAttribute("capyiot.call-budget", b);
      return b;
    }
    return CallBudget.start(Duration.ofSeconds(10), Clock.systemUTC());
  }

  private String actor(AuthorizationContext expected) {
    var authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt token))
      throw ApiProblem.forbidden();
    String actor =
        "capyiot-agent-service".equals(token.getClaimAsString("iss"))
            ? token.getClaimAsString("actorUserId")
            : token.getSubject();
    if (!expected.userId().equals(actor)) throw ApiProblem.forbidden();
    return token.getTokenValue();
  }

  private <T> T read(
      String operation, Object body, String actor, Class<T> output, CallBudget budget) {
    if (!Set.of("reference", "reserve", "commit", "uncertain", "evidence").contains(operation))
      throw new IllegalArgumentException("Fault bridge operation not allowed");
    byte[] bytes;
    try {
      bytes = json.writeValueAsBytes(body);
    } catch (Exception e) {
      throw ApiProblem.invalid("故障请求不符合契约");
    }
    boolean query = Set.of("reference", "evidence").contains(operation);
    for (int attempt = 0; attempt < (query ? 2 : 1); attempt++)
      try {
        var builder =
            HttpRequest.newBuilder(origin.resolve("/internal/fault-bridge/" + operation))
                .timeout(budget.remaining(Duration.ofSeconds(3)))
                .header("X-Core-Service-Key", key)
                .header("Content-Type", "application/json")
                .header(InternalEndpoint.DEADLINE_HEADER, budget.header());
        if (actor != null) builder.header("Authorization", "Bearer " + actor);
        var response =
            transport.exchange(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), budget);
        if (response.status() == 401)
          throw new ApiProblem(401, "FAULT_AUTHORITY_UNAUTHORIZED", "故障授权上下文无效");
        if (response.status() == 409) throw ApiProblem.conflict("故障命令绑定或门禁状态冲突");
        if (response.status() == 400) throw ApiProblem.invalid("故障请求不符合契约");
        if (response.status() == 403) throw ApiProblem.forbidden();
        if (response.status() == 404) throw ApiProblem.missing();
        if (response.status() >= 500)
          throw ServiceFailure.unavailable("FAULT_AUTHORITY_UNAVAILABLE", ServiceId.FAULT, true);
        if (output == Void.class && response.status() >= 200 && response.status() < 300)
          return null;
        if (response.status() != 200)
          throw ServiceFailure.unavailable("FAULT_INVALID_RESPONSE", ServiceId.FAULT, false);
        try {
          T result = json.readValue(response.body(), output);
          if (result == null) throw new IllegalArgumentException();
          budget.check();
          return result;
        } catch (ServiceFailure e) {
          throw e;
        } catch (Exception e) {
          throw ServiceFailure.unavailable("FAULT_INVALID_RESPONSE", ServiceId.FAULT, false);
        }
      } catch (ServiceFailure e) {
        if (!query || !e.retryable() || attempt == 1) throw e;
        budget.check();
      }
    throw new IllegalStateException("Unreachable fault query state");
  }

  public IncidentRef requireIncident(AuthorizationContext actor, String permission, String id) {
    var r =
        read(
            "reference",
            Map.of("incidentId", id, "permission", permission),
            actor(actor),
            FaultApi.IncidentRef.class,
            budget());
    return new IncidentRef(r.incidentId(), r.parkId(), r.status(), r.priority(), r.version());
  }

  public Reservation reserve(
      AuthorizationContext actor,
      String id,
      String operation,
      String key,
      String request,
      String hash) {
    // The old Core cannot prove ABORTED. It must not release reservations on a local exception.
    String operationId =
        UUID.nameUUIDFromBytes(
                (actor.userId() + "|" + id + "|" + operation + "|" + key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    var binding =
        new WorkOrderApi.CommandBinding(
            operationId, id, WorkOrderApi.Operation.valueOf(operation), actor.userId(), key, hash);
    var r =
        read(
            "reserve",
            new FaultApi.Reserve(binding),
            actor(actor),
            FaultApi.Reservation.class,
            budget());
    return new Reservation(
        r.reservationId(),
        id,
        r.state().name(),
        r.workOrderId() == null ? null : Long.parseLong(r.workOrderId()));
  }

  public void commit(String reservation, long order) {
    read(
        "commit",
        Map.of("reservationId", reservation, "workOrderId", Long.toString(order)),
        null,
        Void.class,
        budget());
  }

  public void releaseRolledBack(String reservation, String reason) {
    markUncertain(reservation);
  }

  public void markUncertain(String reservation) {
    read("uncertain", Map.of("reservationId", reservation), null, Void.class, budget());
  }

  public boolean visibleEvidence(
      AuthorizationContext actor, String id, Collection<String> targets) {
    return read(
            "evidence",
            new FaultApi.EvidenceRequest(id, List.copyOf(targets)),
            actor(actor),
            FaultApi.EvidenceResult.class,
            budget())
        .visible();
  }
}
