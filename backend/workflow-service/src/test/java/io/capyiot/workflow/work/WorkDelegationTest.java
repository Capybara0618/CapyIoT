package io.capyiot.workflow.work;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

class WorkDelegationTest {
  Jwt token(String audience) {
    var now = Instant.now();
    return Jwt.withTokenValue("fixture")
        .header("alg", "RS256")
        .header("kid", "key-1")
        .subject("capyiot-agent-service")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .claim("iss", "capyiot-agent-service")
        .claim("aud", List.of(audience))
        .claim("jti", "j-1")
        .claim("command", "CREATE_WORK_ORDER")
        .claim("actorUserId", "user-1")
        .claim("confirmationId", "confirm-1")
        .claim("draftId", "draft-1")
        .claim("draftRevision", 1)
        .claim("requestHash", "a".repeat(64))
        .claim("idempotencyKey", "agent-confirm-1")
        .build();
  }

  @Test
  void workflowAudienceAndExactConfirmedCommandPathsAreRequired() {
    var token = token("capyiot-workflow");
    assertDoesNotThrow(
        () ->
            AgentDelegation.validate(
                token, "POST", "/api/v1/workorders", "agent-confirm-1", false));
    assertDoesNotThrow(
        () ->
            AgentDelegation.validate(
                token,
                "GET",
                "/api/v1/workorders/commands/agent-confirm-1",
                "agent-confirm-1",
                false));
    for (String path :
        List.of(
            "/api/v1/workorders/1/reopen",
            "/api/v1/workorders/validate",
            "/api/v1/workorders/1/cancel"))
      assertThrows(
          ApiProblem.class,
          () -> AgentDelegation.validate(token, "POST", path, "agent-confirm-1", false));
    assertThrows(
        ApiProblem.class,
        () ->
            AgentDelegation.validate(token, "POST", "/api/v1/workorders", "different-key", false));
  }

  @Test
  void oldAudienceWorksOnlyInTheExplicitMigrationWindow() {
    assertThrows(
        ApiProblem.class,
        () ->
            AgentDelegation.validate(
                token("capyiot-core"), "POST", "/api/v1/workorders", "agent-confirm-1", false));
    assertDoesNotThrow(
        () ->
            AgentDelegation.validate(
                token("capyiot-core"), "POST", "/api/v1/workorders", "agent-confirm-1", true));
  }

  @Test
  void rawBodyDigestCannotBeReplacedByReserializingTheDto() throws Exception {
    byte[] body = "{ \"fixture\": 1 }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    var req = new MockHttpServletRequest();
    req.setAttribute(
        "capyiot.delegation",
        new AgentDelegation.Binding("user-1", "confirm-1", "draft-1", 1, hash, "agent-confirm-1"));
    req.setAttribute("capyiot.command-body", body);
    assertDoesNotThrow(() -> AgentDelegation.verifyBody(req));
    req.setAttribute("capyiot.command-body", "changed".getBytes());
    assertThrows(ApiProblem.class, () -> AgentDelegation.verifyBody(req));
  }
}
