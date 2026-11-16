package io.capyiot.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
    private static final DisposableServer DOWNSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.get("/internal/health", (request, response) ->
                    response.header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"code\":\"OK\",\"data\":{\"service\":\"device-service\"}}"))))
            .bindNow();

    @LocalServerPort
    int port;

    @Autowired
    JwtEncoder encoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String hash = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("test-password");
        registry.add("capyiot.auth.jwt-secret", () -> Base64.getEncoder()
                .encodeToString("test-only-signing-key-with-32-bytes".getBytes(StandardCharsets.UTF_8)));
        registry.add("capyiot.auth.admin.password-hash", () -> hash);
        registry.add("capyiot.auth.operator.password-hash", () -> hash);
        registry.add("capyiot.services.device-url", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
        registry.add("management.server.port", () -> 0);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void protectedRouteRequiresAValidToken() {
        client().get().uri("/api/v1/health").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client().get().uri("/api/v1/health").headers(headers -> headers.setBearerAuth("invalid"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void loginRejectsWrongAndMissingCredentials() {
        client().post().uri("/api/v1/auth/login")
                .bodyValue(Map.of("username", "admin", "password", "wrong"))
                .exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.code").isEqualTo("LOGIN_FAILED");
        client().post().uri("/api/v1/auth/login").bodyValue(Map.of("username", "admin"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void issuedTokenRoutesToTheDownstreamAndContainsIdentity() {
        var login = client().post().uri("/api/v1/auth/login")
                .bodyValue(Map.of("username", "operator", "password", "test-password"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.user.role").isEqualTo("OPERATOR")
                .jsonPath("$.data.user.campusId").isEqualTo("CAMPUS-A")
                .returnResult().getResponseBody();
        try {
            String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login)
                    .path("data").path("accessToken").asText();
            client().get().uri("/api/v1/health").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.data.service").isEqualTo("device-service");
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void expiredTokenIsRejected() {
        var claims = JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER).subject("admin")
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        client().get().uri("/api/v1/health").headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isUnauthorized();
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }
}
