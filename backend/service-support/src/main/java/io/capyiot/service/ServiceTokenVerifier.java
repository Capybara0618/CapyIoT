package io.capyiot.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class ServiceTokenVerifier {
  @FunctionalInterface
  public interface Keys {
    RSAKey resolve(ServiceId caller, String kid, CallBudget budget);
  }

  public record Principal(ServiceId caller, InternalEndpoint operation, String tokenId) {}

  private final ServiceId target;
  private final Keys keys;
  private final Clock clock;

  public ServiceTokenVerifier(ServiceId target, Keys keys, Clock clock) {
    this.target = target;
    this.keys = keys;
    this.clock = clock;
  }

  /**
   * Call before controller execution; body bytes and deadline must be the exact incoming request.
   */
  public Principal verify(
      InternalEndpoint operation,
      String method,
      String path,
      String serviceAuthorization,
      ActorCredential actor,
      byte[] body,
      String deadlineHeader,
      CallBudget budget) {
    budget.check();
    if (body == null || body.length > JdkServiceTransport.MAX_RESPONSE_BYTES)
      throw ServiceFailure.denied(400, "CONTRACT_BODY_INVALID");
    if (operation.target() != target || !operation.matches(method, path))
      throw ServiceFailure.denied(403, "SERVICE_SCOPE_DENIED");
    if (serviceAuthorization == null
        || !serviceAuthorization.startsWith("Bearer ")
        || serviceAuthorization.length() > 16391)
      throw ServiceFailure.denied(401, "SERVICE_TOKEN_INVALID");
    try {
      var jwt = SignedJWT.parse(serviceAuthorization.substring(7));
      var header = jwt.getHeader();
      if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
          || !JOSEObjectType.JWT.equals(header.getType())
          || header.getKeyID() == null
          || !header.getKeyID().matches("[A-Za-z0-9_-]{1,128}")
          || !Set.of("alg", "typ", "kid").containsAll(header.toJSONObject().keySet()))
        throw invalid();
      var claims = jwt.getJWTClaimsSet();
      var caller = ServiceId.fromIssuer(claims.getIssuer());
      var key = keys.resolve(caller, header.getKeyID(), budget);
      if (key == null
          || key.size() < 2048
          || !header.getKeyID().equals(key.getKeyID())
          || !jwt.verify(new RSASSAVerifier(key))) throw invalid();
      var issued = claims.getIssueTime();
      var expires = claims.getExpirationTime();
      var notBefore = claims.getNotBeforeTime();
      var now = clock.instant();
      if (!caller.value().equals(claims.getSubject())
          || !List.of(target.audience()).equals(claims.getAudience())
          || issued == null
          || expires == null
          || !expires.toInstant().isAfter(now)
          || issued.toInstant().isAfter(now.plusSeconds(5))
          || !expires.after(issued)
          || Duration.between(issued.toInstant(), expires.toInstant())
                  .compareTo(Duration.ofSeconds(120))
              > 0
          || notBefore != null && notBefore.toInstant().isAfter(now.plusSeconds(5))
          || claims.getJWTID() == null
          || !claims.getJWTID().matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
      if (!operation.allows(caller)
          || !operation.name().equals(claims.getStringClaim("operation"))
          || !operation.scope().equals(claims.getStringClaim("scope"))
          || !method.equals(claims.getStringClaim("method"))
          || !path.equals(claims.getStringClaim("path")))
        throw ServiceFailure.denied(403, "SERVICE_SCOPE_DENIED");
      Object deadline = claims.getClaim("deadline");
      if (!(deadline instanceof Long || deadline instanceof Integer)
          || !deadline.toString().equals(deadlineHeader)
          || !ActorCredential.digest(body).equals(claims.getStringClaim("bodyDigest")))
        throw invalid();
      long deadlineMillis = ((Number) deadline).longValue();
      // NumericDate iat is encoded to whole seconds; the signed deadline retains milliseconds.
      // The receiver still caps its actual execution budget to MAXIMUM.
      if (deadlineMillis
          > issued.toInstant().plus(CallBudget.MAXIMUM).plusMillis(999).toEpochMilli())
        throw invalid();
      if (deadlineMillis <= now.toEpochMilli())
        throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT", target, false);
      if (operation.actorRequired() != (actor != null)
          || actor != null && !actor.digest().equals(claims.getStringClaim("actorDigest"))
          || actor == null && claims.getClaim("actorDigest") != null) throw invalid();
      budget.check();
      return new Principal(caller, operation, claims.getJWTID());
    } catch (ServiceFailure error) {
      throw error;
    } catch (Exception error) {
      throw invalid();
    }
  }

  private static ServiceFailure invalid() {
    return ServiceFailure.denied(401, "SERVICE_TOKEN_INVALID");
  }
}
