package io.capyiot.security;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveUserJwtDecoderTest {
    HttpServer server;RSAKey key;LiveUserJwtDecoder decoder;
    AtomicInteger status=new AtomicInteger(200);
    AtomicReference<String> context=new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        key=new RSAKeyGenerator(2048).keyID("trusted-user-key").generate();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});
        server.createContext("/internal/foundation/context",exchange->{
            assertNotNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("test-internal-key-with-more-than-32-characters",exchange.getRequestHeaders().getFirst("X-Core-Service-Key"));
            byte[] body=Objects.toString(context.get(),"{}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();decoder=new LiveUserJwtDecoder(base+"/jwks",base,"test-internal-key-with-more-than-32-characters");
    }
    String token(){var claims=JwtClaimsSet.builder().issuer("capyiot-gateway").subject("stable-user-id").audience(List.of("capyiot-api"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).id("jti").claim("sid","session").claim("authz_ver",1)
            .claim("roles",List.of("ADMIN")).claim("legacyLoginName","untrusted-name").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(),claims)).getTokenValue();}
    void grant(String role,String scope,String park){context.set("{\"userId\":\"stable-user-id\",\"loginName\":\"operator\",\"grants\":[{\"roleCode\":\""+role+"\",\"scopeType\":\""+scope+"\",\"parkId\":\""+park+"\"}]}");}
    @Test void fullParkGrantMapsLegacyRoleWithoutChangingStableSubject(){
        grant("OPS_SUPERVISOR","PARK","CAMPUS-A");Jwt result=decoder.decode(token());
        assertEquals("stable-user-id",result.getSubject());assertEquals(List.of("ADMIN"),result.getClaimAsStringList("roles"));
        assertEquals("operator",LiveUserJwtDecoder.legacyLoginName(result));
    }
    @Test void tokenRoleHintsAndPartialScopesCannotAuthorizeLegacyControllers(){
        for(String[] grant:List.of(new String[]{"OPS_OPERATOR","AREA_SUBTREE","CAMPUS-A"},new String[]{"SYS_ADMIN","NONE","CAMPUS-A"},new String[]{"OPS_SUPERVISOR","PARK","PARK-B"})){
            grant(grant[0],grant[1],grant[2]);assertTrue(decoder.decode(token()).getClaimAsStringList("roles").isEmpty());
        }
    }
    @Test void revokedAndUnavailableAuthorityFailClosed(){grant("OPS_OPERATOR","PARK","CAMPUS-A");String value=token();for(int failure:List.of(401,503)){status.set(failure);assertThrows(BadJwtException.class,()->decoder.decode(value));}}
    @Test void mismatchedContextIdentityFailsClosed(){context.set("{\"userId\":\"someone-else\",\"loginName\":\"operator\",\"grants\":[]}");assertThrows(BadJwtException.class,()->decoder.decode(token()));}
    @AfterEach void stop(){server.stop(0);}
}
