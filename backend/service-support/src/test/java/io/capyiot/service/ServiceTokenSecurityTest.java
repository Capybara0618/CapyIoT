package io.capyiot.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class ServiceTokenSecurityTest {
    static RSAKey key;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-26T06:00:00Z"),ZoneOffset.UTC);
    final byte[] body="{}".getBytes(StandardCharsets.UTF_8);
    @BeforeAll static void signingKey() throws Exception {key=new RSAKeyGenerator(2048).keyID("workflow-k1").generate();}
    CallBudget budget(){return CallBudget.start(Duration.ofSeconds(10),clock);}
    ServiceTokenSigner signer(){return new ServiceTokenSigner(ServiceId.WORKFLOW,()->key,clock,Duration.ofSeconds(60));}
    ServiceTokenVerifier verifier(ServiceId target) {
        return new ServiceTokenVerifier(target,(caller,kid,b)->caller==ServiceId.WORKFLOW?key.toPublicJWK():null,clock);
    }
    String signed(InternalEndpoint endpoint,String path,ActorCredential actor,CallBudget budget) {
        return "Bearer "+signer().sign(endpoint,path,actor,body,budget);
    }
    String resign(String original,JWTClaimsSet claims) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),claims);
        jwt.sign(new RSASSASigner(key));return "Bearer "+jwt.serialize();
    }
    JWTClaimsSet claims(String authorization) throws Exception{return SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet();}

    @Test void credentialIsBoundToCallerTargetEndpointBodyActorAndDeadline() {
        var endpoint=InternalEndpoint.RESERVATION_CREATE;String path=endpoint.path("incident-1");
        var actor=ActorCredential.bearer("Bearer actor.payload.signature");var budget=budget();
        String token=signed(endpoint,path,actor,budget);
        var principal=verifier(ServiceId.FAULT).verify(endpoint,"POST",path,token,actor,body,budget.header(),budget);
        assertThat(principal.caller()).isEqualTo(ServiceId.WORKFLOW);
        var differentActor=ActorCredential.bearer("Bearer other.payload.signature");
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,token,differentActor,body,budget.header(),budget))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(401));
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,token,actor,new byte[]{1},budget.header(),budget))
                .isInstanceOf(ServiceFailure.class);
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",endpoint.path("incident-2"),token,actor,body,budget.header(),budget))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(403));
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,token,actor,body,"1",budget))
                .isInstanceOf(ServiceFailure.class);
    }

    @Test void validSignatureDoesNotPermitWrongAudienceOrIssuer() throws Exception {
        var endpoint=InternalEndpoint.RESERVATION_COMMIT;String path=endpoint.path("reservation-1");var budget=budget();
        String original=signed(endpoint,path,null,budget);var originalClaims=claims(original);
        String wrongAudience=resign(original,new JWTClaimsSet.Builder(originalClaims).audience(List.of(ServiceId.DEVICE.audience())).build());
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,wrongAudience,null,body,budget.header(),budget))
                .isInstanceOf(ServiceFailure.class);
        String wrongIssuer=resign(original,new JWTClaimsSet.Builder(originalClaims).issuer("https://untrusted.invalid").build());
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,wrongIssuer,null,body,budget.header(),budget))
                .isInstanceOf(ServiceFailure.class);
        String overbroad=resign(original,new JWTClaimsSet.Builder(originalClaims)
                .expirationTime(java.util.Date.from(clock.instant().plusSeconds(121))).build());
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,overbroad,null,body,budget.header(),budget))
                .isInstanceOf(ServiceFailure.class);
    }

    @Test void missingServiceTokenAndDisallowedAgentCallerFailClosed() {
        var endpoint=InternalEndpoint.RESERVATION_COMMIT;String path=endpoint.path("reservation-1");var budget=budget();
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,null,null,body,budget.header(),budget))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(401));
        var agent=new ServiceTokenSigner(ServiceId.AGENT,()->key,clock,Duration.ofSeconds(60));
        assertThatThrownBy(()->agent.sign(endpoint,path,null,body,budget)).isInstanceOfSatisfying(ServiceFailure.class,
                error->assertThat(error.status()).isEqualTo(403));
    }

    @Test void expiredTokenAndAlgorithmConfusionFailBeforeBusinessExecution() throws Exception {
        var endpoint=InternalEndpoint.RESERVATION_COMMIT;String path=endpoint.path("reservation-1");var budget=budget();
        String original=signed(endpoint,path,null,budget);var originalClaims=claims(original);
        String expired=resign(original,new JWTClaimsSet.Builder(originalClaims)
                .issueTime(java.util.Date.from(clock.instant().minusSeconds(61)))
                .expirationTime(java.util.Date.from(clock.instant().minusSeconds(1))).build());
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,expired,null,body,budget.header(),budget))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(401));
        var hs=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),originalClaims);
        hs.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32]));
        assertThatThrownBy(()->verifier(ServiceId.FAULT).verify(endpoint,"POST",path,"Bearer "+hs.serialize(),null,body,budget.header(),budget))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(401));
    }

    @Test void publicJwksAndDiagnosticsNeverExposePrivateKeyOrActorToken(@TempDir Path directory) throws Exception {
        Path file=directory.resolve("keys.json");Files.writeString(file,new JWKSet(key).toString(false));
        var ring=new ServiceKeyRing(file);var publicSet=JWKSet.parse(ring.publicJwks());
        assertThat(publicSet.getKeys()).allMatch(k->!k.isPrivate());
        var actor=ActorCredential.bearer("Bearer sensitive.payload.signature");
        assertThat(actor.toString()).doesNotContain("sensitive","payload","signature");
        assertThat(ring.toString()).doesNotContain(file.toString());
    }

    @Test void jwksLookupUsesOnlyConfiguredIssuerAndBoundsRefresh() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        byte[] publicKeys=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        var keys=new RemoteServiceKeys(Map.of(ServiceId.WORKFLOW,java.net.URI.create("https://workflow-service/.well-known/service-jwks.json")),
                (request,b)->{requests.incrementAndGet();return new ServiceTransport.Result(200,publicKeys);},clock);
        assertThat(keys.resolve(ServiceId.WORKFLOW,key.getKeyID(),budget()).getKeyID()).isEqualTo(key.getKeyID());
        assertThat(keys.resolve(ServiceId.WORKFLOW,key.getKeyID(),budget()).getKeyID()).isEqualTo(key.getKeyID());
        assertThat(requests).hasValue(1);
        assertThatThrownBy(()->keys.resolve(ServiceId.WORKFLOW,"unknown-kid",budget())).isInstanceOf(ServiceFailure.class);
        assertThatThrownBy(()->keys.resolve(ServiceId.AGENT,key.getKeyID(),budget())).isInstanceOf(ServiceFailure.class);
        assertThat(requests).hasValue(1);
    }
}
