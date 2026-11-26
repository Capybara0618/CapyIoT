package io.capyiot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
    private static final AtomicBoolean REVOKED=new AtomicBoolean();
    private static final AtomicBoolean UNAVAILABLE=new AtomicBoolean();
    private static final String SERVICE_KEY="test-core-service-key-with-at-least-32-characters";
    private static final String KEY_FILE=keyFile();
    private static String keyFile() {
        try { return Files.createTempDirectory("capyiot-gateway-test").resolve("keys.json").toString(); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    private static final DisposableServer DOWNSTREAM=HttpServer.create().host("127.0.0.1").port(0).route(routes->routes
        .get("/api/v1/health",(req,res)->res.header("Content-Type","application/json").sendString(Mono.just("{\"code\":\"OK\",\"data\":{\"service\":\"core-service\"}}")))
        .post("/internal/foundation/login",(req,res)->req.receive().aggregate().asString().flatMap(body->{
            if(!SERVICE_KEY.equals(req.requestHeaders().get("X-Core-Service-Key"))||!body.contains("test-password")) return res.status(401).send().then();
            String json="{\"userId\":\"USER-OPERATOR\",\"loginName\":\"operator\",\"displayName\":\"Operator\",\"authorizationVersion\":1,\"sessionId\":\"session-1\",\"jti\":\"token-1\",\"expiresAt\":\""+Instant.now().plusSeconds(3600)+"\",\"roleCodes\":[\"OPS_OPERATOR\"]}";
            return res.header("Content-Type","application/json").sendString(Mono.just(json)).then();
        }))
        .get("/internal/foundation/context",(req,res)->{
            if(UNAVAILABLE.get()) return res.status(503).send();
            if(REVOKED.get()) return res.status(401).send();
            return res.header("Content-Type","application/json").sendString(Mono.just("{\"userId\":\"USER-OPERATOR\",\"grants\":[]}"));
        })).bindNow();
    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired UserKeyRing keys;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("capyiot.auth.key-file",()->KEY_FILE); r.add("capyiot.auth.generate-key",()->true);
        r.add("capyiot.auth.core-service-key",()->SERVICE_KEY);
        r.add("capyiot.auth.platform-http-test-only",()->true);
        r.add("capyiot.services.core-url",()->"http://127.0.0.1:"+DOWNSTREAM.port());
        r.add("capyiot.services.platform-url",()->"http://127.0.0.1:"+DOWNSTREAM.port());
        r.add("capyiot.services.device-url",()->"http://127.0.0.1:"+DOWNSTREAM.port());
        r.add("management.server.port",()->0);
    }
    private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
    private String login() throws Exception {
        byte[] body=client().post().uri("/api/v1/auth/login").bodyValue(Map.of("loginName","operator","password","test-password"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.user.userId").isEqualTo("USER-OPERATOR")
            .jsonPath("$.data.user.authorizationVersion").isEqualTo(1).returnResult().getResponseBody();
        return new ObjectMapper().readTree(body).path("data").path("accessToken").asText();
    }
    @Test void protectedRouteRequiresAValidToken() {
        client().get().uri("/api/v1/health").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/v1/health").headers(h->h.setBearerAuth("invalid")).exchange().expectStatus().isUnauthorized();
    }
    @Test void loginRejectsWrongAndMissingCredentials() {
        client().post().uri("/api/v1/auth/login").bodyValue(Map.of("loginName","admin","password","wrong"))
            .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("LOGIN_FAILED");
        client().post().uri("/api/v1/auth/login").bodyValue(Map.of("loginName","admin")).exchange().expectStatus().isBadRequest();
    }
    @Test void issuedRs256TokenRoutesWithStableIdentity() throws Exception {
        String token=login(); var jwt=keys.decode(token);
        Assertions.assertEquals("USER-OPERATOR",jwt.getSubject());
        Assertions.assertEquals(List.of("capyiot-api"),jwt.getAudience());
        Assertions.assertEquals("session-1",jwt.getClaimAsString("sid"));
        Assertions.assertEquals("RS256",jwt.getHeaders().get("alg"));
        client().get().uri("/api/v1/health").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.service").isEqualTo("core-service");
    }
    @Test void revokedSessionAndUnavailableAuthorityFailClosed() throws Exception {
        String token=login();
        try {
            REVOKED.set(true);
            client().get().uri("/api/v1/health").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
            REVOKED.set(false); UNAVAILABLE.set(true);
            client().get().uri("/api/v1/health").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isEqualTo(503);
        } finally { REVOKED.set(false); UNAVAILABLE.set(false); }
    }
    @Test void expiredAndWrongAudienceTokensAreRejected() {
        for(boolean expired:List.of(true,false)) {
            Instant now=Instant.now();
            var claims=JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER).subject("USER-OPERATOR")
                .audience(List.of(expired?"capyiot-api":"other-service")).id("token-1").claim("sid","session-1").claim("authz_ver",1)
                .issuedAt(now.minusSeconds(7200)).expiresAt(expired?now.minusSeconds(3600):now.plusSeconds(3600)).build();
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeId()).build(),claims)).getTokenValue();
            client().get().uri("/api/v1/health").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
        }
    }
    @Test void publishedJwksContainsNoPrivateMaterial() {
        client().get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.keys[0].kid").exists().jsonPath("$.keys[0].n").exists().jsonPath("$.keys[0].d").doesNotExist()
            .jsonPath("$.keys[0].p").doesNotExist();
    }
    @AfterAll static void stopDownstream() { DOWNSTREAM.disposeNow(); }
}
