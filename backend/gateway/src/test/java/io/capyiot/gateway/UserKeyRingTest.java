package io.capyiot.gateway;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class UserKeyRingTest {
    @TempDir Path directory;
    private String token(UserKeyRing ring) {
        var claims=JwtClaimsSet.builder().issuer("capyiot-gateway").subject("stable-user-id").audience(List.of("capyiot-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).id("jti")
                .claim("sid","session").claim("authz_ver",1).build();
        return ring.encoder().encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(ring.activeId()).build(),claims)).getTokenValue();
    }
    @Test void restartPreservesKeysAndRotationAcceptsOnlyPublishedKeys() throws Exception {
        Path file=directory.resolve("keys.json"); var ring=new UserKeyRing(file.toString(),true);
        String old=token(ring),oldId=ring.activeId();
        assertEquals(oldId,new UserKeyRing(file.toString(),false).activeId());
        JWK previous=ring.keys().getKeys().getFirst();
        RSAKey next=new RSAKeyGenerator(2048).keyID("next").generate();
        Files.writeString(file,new JWKSet(List.of(next,previous.toPublicJWK())).toString(false));
        assertEquals("stable-user-id",ring.decode(old).getSubject());
        String current=token(ring); assertEquals("next",ring.decode(current).getHeaders().get("kid"));
        Files.writeString(file,new JWKSet(next).toString(false));
        assertThrows(BadJwtException.class,()->ring.decode(old));
        assertEquals("stable-user-id",ring.decode(current).getSubject());
    }
    @Test void unavailableOrInvalidKeyFilesFailClosed() throws Exception {
        Path missing=directory.resolve("missing.json");
        assertThrows(IllegalStateException.class,()->new UserKeyRing(missing.toString(),false));
        Files.writeString(missing,"{}");
        assertThrows(RuntimeException.class,()->new UserKeyRing(missing.toString(),false));
    }
}
