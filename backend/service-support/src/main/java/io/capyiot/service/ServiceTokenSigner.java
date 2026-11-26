package io.capyiot.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

public final class ServiceTokenSigner {
    private final ServiceId caller;
    private final Supplier<RSAKey> keys;
    private final Clock clock;
    private final Duration lifetime;
    public ServiceTokenSigner(ServiceId caller,ServiceKeyRing keys,Clock clock) {
        this(caller,keys::active,clock,Duration.ofSeconds(60));
    }
    ServiceTokenSigner(ServiceId caller,Supplier<RSAKey> keys,Clock clock,Duration lifetime) {
        if(lifetime==null||lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofSeconds(120))>0)
            throw new IllegalArgumentException("Invalid service token lifetime");
        this.caller=java.util.Objects.requireNonNull(caller);this.keys=keys;this.clock=clock;this.lifetime=lifetime;
    }
    public ServiceId caller(){return caller;}
    public String sign(InternalEndpoint operation,String path,ActorCredential actor,byte[] body,CallBudget budget) {
        budget.check();
        if(!operation.allows(caller)||!operation.matches(operation.method(),path))throw ServiceFailure.denied(403,"SERVICE_SCOPE_DENIED");
        if(operation.actorRequired()!=(actor!=null))throw ServiceFailure.denied(401,"ACTOR_TOKEN_INVALID");
        var now=clock.instant();
        try {
            var key=keys.get();
            var claims=new JWTClaimsSet.Builder().issuer(caller.issuer()).subject(caller.value())
                    .audience(operation.target().audience()).issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString()).claim("operation",operation.name()).claim("scope",operation.scope())
                    .claim("method",operation.method()).claim("path",path).claim("bodyDigest",ActorCredential.digest(body))
                    .claim("deadline",budget.deadline().toEpochMilli());
            if(actor!=null)claims.claim("actorDigest",actor.digest());
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),claims.build());
            jwt.sign(new RSASSASigner(key));return jwt.serialize();
        }catch(Exception error){throw ServiceFailure.unavailable("SERVICE_SIGNING_UNAVAILABLE",operation.target(),false);}
    }
}
