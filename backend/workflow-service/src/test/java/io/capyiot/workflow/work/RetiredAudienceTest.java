package io.capyiot.workflow.work;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import io.capyiot.service.ServiceTokenVerifier;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

/** The removed migration window cannot be re-enabled through an environment flag. */
class RetiredAudienceTest {
  @Test
  void onlyWorkflowAudienceIsAcceptedForAConfirmedAgentIdentity() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("fixture-agent").generate();
    ServiceTokenVerifier.Keys keys = (caller, kid, budget) -> key.toPublicJWK();
    var decoder = new WorkJwtDecoder(keys, Clock.systemUTC());
    for (String audience : List.of("capyiot-workflow", "capyiot-core", "capyiot-api")) {
      var now = new Date();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("capyiot-agent-service")
              .subject("capyiot-agent-service")
              .audience(audience)
              .issueTime(now)
              .expirationTime(new Date(now.getTime() + 60000))
              .jwtID("fixture-id")
              .claim("command", "CREATE_WORK_ORDER")
              .claim("actorUserId", "user-1")
              .claim("confirmationId", "confirmation-1")
              .claim("draftId", "draft-1")
              .claim("draftRevision", 1)
              .claim("requestHash", "a".repeat(64))
              .claim("idempotencyKey", "agent-confirmation-1")
              .build();
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      if (audience.equals("capyiot-workflow"))
        assertDoesNotThrow(() -> decoder.decode(jwt.serialize()));
      else assertThrows(BadJwtException.class, () -> decoder.decode(jwt.serialize()));
    }
  }
}
