package io.capyiot.core.ports;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.platform.AuthorizationContext;
import io.capyiot.service.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;

class RemoteFaultGateTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @BeforeEach
  void request() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("a.b.c").header("alg", "RS256").subject("user-1").build()));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  AuthorizationContext actor() {
    return new AuthorizationContext("user-1", "u", "User", 1, "session", List.of());
  }

  RemoteFaultGate client(ServiceTransport transport) {
    return new RemoteFaultGate(
        URI.create("https://alarm-service:8083"),
        "fixture-bridge-key-with-at-least-32-characters",
        transport,
        json);
  }

  @Test
  void readQueriesRetryAtMostOnceButReservationCommandsDoNot() {
    var attempts = new AtomicInteger();
    var remote =
        client(
            (r, b) -> {
              attempts.incrementAndGet();
              throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT", null, true);
            });
    assertThrows(
        ServiceFailure.class,
        () -> remote.requireIncident(actor(), "workorder.create", "incident-1"));
    assertEquals(2, attempts.get());
    attempts.set(0);
    assertThrows(
        ServiceFailure.class,
        () ->
            remote.reserve(actor(), "incident-1", "CREATE", "key-1", "request-1", "a".repeat(64)));
    assertEquals(1, attempts.get());
  }

  @Test
  void oldRollbackNotificationNeverCallsAnAbortEndpoint() {
    var calls = new AtomicInteger();
    var remote =
        client(
            (r, b) -> {
              calls.incrementAndGet();
              assertEquals("/internal/fault-bridge/uncertain", r.uri().getPath());
              assertTrue(r.headers().firstValue("Authorization").isEmpty());
              return new ServiceTransport.Result(204, new byte[0]);
            });
    remote.releaseRolledBack("reservation-1", "local exception");
    assertEquals(1, calls.get());
  }

  @Test
  void arbitraryActorAndPlaintextAuthorityAreRejectedBeforeCalling() {
    var calls = new AtomicInteger();
    var remote =
        client(
            (r, b) -> {
              calls.incrementAndGet();
              return null;
            });
    var other = new AuthorizationContext("different", "u", "U", 1, "s", List.of());
    assertThrows(
        ApiProblem.class, () -> remote.requireIncident(other, "workorder.create", "incident-1"));
    assertEquals(0, calls.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteFaultGate(
                URI.create("http://alarm-service"),
                "fixture-bridge-key-with-at-least-32-characters",
                (r, b) -> null,
                json));
  }

  @Test
  void missingAuthorityIsAnErrorAndNeverAnEmptySuccessfulResult() {
    var remote = client((r, b) -> new ServiceTransport.Result(404, new byte[0]));
    assertEquals(
        404,
        assertThrows(
                ApiProblem.class,
                () -> remote.requireIncident(actor(), "workorder.create", "incident-1"))
            .status());
  }
}
