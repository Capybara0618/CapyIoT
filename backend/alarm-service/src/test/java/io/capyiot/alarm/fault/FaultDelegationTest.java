package io.capyiot.alarm.fault;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class FaultDelegationTest {
  ObjectMapper json = new ObjectMapper();

  Jwt token() {
    return Jwt.withTokenValue("delegated-fixture")
        .header("alg", "RS256")
        .subject("capyiot-agent-service")
        .claim("actorUserId", "user-1")
        .claim("requestHash", "a".repeat(64))
        .claim("idempotencyKey", "agent-confirmation-1")
        .build();
  }

  @Test
  void confirmedDelegationMustMatchActorHashKeyAndCreateOperation() {
    var b =
        new WorkOrderApi.CommandBinding(
            "op-1",
            "incident-1",
            WorkOrderApi.Operation.CREATE,
            "user-1",
            "agent-confirmation-1",
            "a".repeat(64));
    assertDoesNotThrow(
        () ->
            FaultSecurity.checkDelegatedBinding(
                token(), json.valueToTree(new FaultApi.Reserve(b))));
    for (var changed :
        java.util.List.of(
            new WorkOrderApi.CommandBinding(
                "op-1",
                "incident-1",
                WorkOrderApi.Operation.REOPEN,
                "user-1",
                b.idempotencyKey(),
                b.requestHash()),
            new WorkOrderApi.CommandBinding(
                "op-1",
                "incident-1",
                b.operation(),
                "other-user",
                b.idempotencyKey(),
                b.requestHash()),
            new WorkOrderApi.CommandBinding(
                "op-1",
                "incident-1",
                b.operation(),
                b.actorUserId(),
                "agent-other",
                b.requestHash()),
            new WorkOrderApi.CommandBinding(
                "op-1",
                "incident-1",
                b.operation(),
                b.actorUserId(),
                b.idempotencyKey(),
                "b".repeat(64))))
      assertThrows(
          ApiProblem.class,
          () ->
              FaultSecurity.checkDelegatedBinding(
                  token(), json.valueToTree(new FaultApi.Reserve(changed))));
  }
}
