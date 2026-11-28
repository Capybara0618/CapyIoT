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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
  private static final AtomicBoolean REVOKED = new AtomicBoolean();
  private static final AtomicBoolean UNAVAILABLE = new AtomicBoolean();
  private static final java.util.concurrent.atomic.AtomicInteger READ_ATTEMPTS = new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger WRITE_ATTEMPTS = new java.util.concurrent.atomic.AtomicInteger();
  private static final com.nimbusds.jose.jwk.RSAKey SERVICE_KEY = serviceKey();

  private static com.nimbusds.jose.jwk.RSAKey serviceKey() {
    try {
      return new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
          .keyID("gateway-service-test")
          .generate();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static final String SERVICE_KEY_FILE = serviceKeyFile();

  private static String serviceKeyFile() {
    try {
      var path = Files.createTempDirectory("gateway-service-test").resolve("service.json");
      Files.writeString(path, new com.nimbusds.jose.jwk.JWKSet(SERVICE_KEY).toString(false));
      return path.toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static boolean serviceIdentity(
      String authorization, String actor, String body, String operation) {
    try {
      var token = com.nimbusds.jwt.SignedJWT.parse(authorization.substring(7));
      var claims = token.getJWTClaimsSet();
      var hash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      boolean valid =
          token.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(SERVICE_KEY.toRSAPublicKey()))
              && claims.getIssuer().equals("capyiot-service/gateway")
              && claims.getSubject().equals("gateway")
              && claims.getAudience().equals(List.of("capyiot-service/platform-service"))
              && claims.getStringClaim("operation").equals(operation)
              && claims.getStringClaim("bodyDigest").equals(hash);
      if (actor != null)
        valid &=
            claims
                .getStringClaim("actorDigest")
                .equals(
                    java.util.HexFormat.of()
                        .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(
                                    actor
                                        .substring(7)
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
      return valid;
    } catch (Exception error) {
      return false;
    }
  }

  private static String envelope(String data) {
    return "{\"code\":\"OK\",\"traceId\":\"test\",\"data\":" + data + "}";
  }

  private static final String KEY_FILE = keyFile();

  private static String keyFile() {
    try {
      return Files.createTempDirectory("capyiot-gateway-test").resolve("keys.json").toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static final DisposableServer DOWNSTREAM =
      HttpServer.create()
          .host("127.0.0.1")
          .port(0)
          .route(
              routes ->
                  routes
                      .get("/api/v1/auth/me", (req, res) -> {
                        if (READ_ATTEMPTS.incrementAndGet() == 1)
                          return res.status(503).header("Connection", "close").send();
                        return res.header("Content-Type", "application/json").sendString(Mono.just(envelope("{\"userId\":\"USER-OPERATOR\"}")));
                      })
                      .post("/api/v1/auth/logout", (req, res) -> {
                        WRITE_ATTEMPTS.incrementAndGet();
                        if ("true".equals(req.requestHeaders().get("X-Test-Transport-Close"))) {
                          res.withConnection(connection -> connection.dispose());
                          return Mono.empty();
                        }
                        return res.status(503).header("Connection", "close").send();
                      })
                      .get(
                          "/api/v1/health",
                          (req, res) ->
                              res.header("Content-Type", "application/json")
                                  .sendString(
                                      Mono.just(
                                          "{\"code\":\"OK\",\"data\":{\"service\":\"device-service\"}}")))
                      .post(
                          "/internal/v1/identity/login",
                          (req, res) ->
                              req.receive()
                                  .aggregate()
                                  .asString()
                                  .flatMap(
                                      body -> {
                                        if (!serviceIdentity(
                                                req.requestHeaders().get("X-Service-Authorization"),
                                                null,
                                                body,
                                                "PLATFORM_LOGIN")
                                            || !body.contains("test-password"))
                                          return res.status(401).send().then();
                                        String json =
                                            "{\"userId\":\"USER-OPERATOR\",\"loginName\":\"operator\",\"displayName\":\"Operator\",\"authorizationVersion\":1,\"sessionId\":\"session-1\",\"jti\":\"token-1\",\"expiresAt\":\""
                                                + Instant.now().plusSeconds(3600)
                                                + "\",\"roleCodes\":[\"OPS_OPERATOR\"]}";
                                        return res.header("Content-Type", "application/json")
                                            .sendString(Mono.just(envelope(json)))
                                            .then();
                                      }))
                      .post(
                          "/internal/v1/identity/context",
                          (req, res) ->
                              req.receive()
                                  .aggregate()
                                  .asString()
                                  .flatMap(
                                      body -> {
                                        if (!serviceIdentity(
                                            req.requestHeaders().get("X-Service-Authorization"),
                                            req.requestHeaders().get("Authorization"),
                                            body,
                                            "IDENTITY_CONTEXT"))
                                          return res.status(401).send().then();
                                        if (UNAVAILABLE.get()) return res.status(503).send().then();
                                        if (REVOKED.get()) return res.status(401).send().then();
                                        return res.header("Content-Type", "application/json")
                                            .sendString(
                                                Mono.just(
                                                    envelope(
                                                        "{\"userId\":\"USER-OPERATOR\",\"loginName\":\"operator\",\"displayName\":\"Operator\",\"authorizationVersion\":1,\"sessionId\":\"session-1\",\"grants\":[],\"permissionCodes\":[],\"checkedAt\":\""
                                                            + Instant.now()
                                                            + "\"}")))
                                            .then();
                                      })))
          .bindNow();
  @LocalServerPort int port;
  @Autowired JwtEncoder encoder;
  @Autowired UserKeyRing keys;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("routes")
  org.springframework.cloud.gateway.route.RouteLocator routes;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("capyiot.auth.key-file", () -> KEY_FILE);
    r.add("capyiot.auth.generate-key", () -> true);
    r.add("capyiot.auth.service-key-file", () -> SERVICE_KEY_FILE);
    r.add(
        "capyiot.auth.session-proxy-key", () -> "test-session-proxy-secret-at-least-32-characters");
    r.add("capyiot.auth.platform-http-test-only", () -> true);
    r.add("capyiot.services.platform-url", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
    r.add("capyiot.services.device-url", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
    for (String service : List.of("ingestion", "alarm", "workflow", "agent"))
      r.add("capyiot.services." + service + "-url", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
    r.add("management.server.port", () -> 0);
  }

  private WebTestClient client() {
    return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
  }

  private String login() throws Exception {
    byte[] body =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .bodyValue(Map.of("loginName", "operator", "password", "test-password"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.user.userId")
            .isEqualTo("USER-OPERATOR")
            .jsonPath("$.data.user.authorizationVersion")
            .isEqualTo(1)
            .returnResult()
            .getResponseBody();
    return new ObjectMapper().readTree(body).path("data").path("accessToken").asText();
  }

  @Test
  void drainingPlatformReadRetriesOnANewConnectionButCommandsDoNotReplay() throws Exception {
    READ_ATTEMPTS.set(0); WRITE_ATTEMPTS.set(0);
    String token = login();
    client().get().uri("/api/v1/auth/me").headers(h -> h.setBearerAuth(token))
        .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.userId").isEqualTo("USER-OPERATOR");
    Assertions.assertEquals(2, READ_ATTEMPTS.get());
    client().post().uri("/api/v1/auth/logout").headers(h -> h.setBearerAuth(token))
        .exchange().expectStatus().isEqualTo(503);
    Assertions.assertEquals(1, WRITE_ATTEMPTS.get());
    WRITE_ATTEMPTS.set(0);
    client().post().uri("/api/v1/auth/logout").headers(h -> h.setBearerAuth(token))
        .header("X-Test-Transport-Close", "true").exchange().expectStatus().isEqualTo(503);
    Assertions.assertEquals(1, WRITE_ATTEMPTS.get());
  }

  @Test
  void protectedRouteRequiresAValidToken() {
    client().get().uri("/api/v1/health").exchange().expectStatus().isUnauthorized();
    client()
        .get()
        .uri("/api/v1/health")
        .headers(h -> h.setBearerAuth("invalid"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void loginRejectsWrongAndMissingCredentials() {
    client()
        .post()
        .uri("/api/v1/auth/login")
        .bodyValue(Map.of("loginName", "admin", "password", "wrong"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("LOGIN_FAILED");
    client()
        .post()
        .uri("/api/v1/auth/login")
        .bodyValue(Map.of("loginName", "admin"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void issuedRs256TokenRoutesWithStableIdentity() throws Exception {
    String token = login();
    var jwt = keys.decode(token);
    Assertions.assertEquals("USER-OPERATOR", jwt.getSubject());
    Assertions.assertEquals(List.of("capyiot-api"), jwt.getAudience());
    Assertions.assertEquals("session-1", jwt.getClaimAsString("sid"));
    Assertions.assertEquals("RS256", jwt.getHeaders().get("alg"));
    client()
        .get()
        .uri("/api/v1/health")
        .headers(h -> h.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.service")
        .isEqualTo("device-service");
  }

  @Test
  void revokedSessionAndUnavailableAuthorityFailClosed() throws Exception {
    String token = login();
    try {
      REVOKED.set(true);
      client()
          .get()
          .uri("/api/v1/health")
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isUnauthorized();
      REVOKED.set(false);
      UNAVAILABLE.set(true);
      client()
          .get()
          .uri("/api/v1/health")
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isEqualTo(503);
    } finally {
      REVOKED.set(false);
      UNAVAILABLE.set(false);
    }
  }

  @Test
  void expiredAndWrongAudienceTokensAreRejected() {
    for (boolean expired : List.of(true, false)) {
      Instant now = Instant.now();
      var claims =
          JwtClaimsSet.builder()
              .issuer(SecurityConfiguration.ISSUER)
              .subject("USER-OPERATOR")
              .audience(List.of(expired ? "capyiot-api" : "other-service"))
              .id("token-1")
              .claim("sid", "session-1")
              .claim("authz_ver", 1)
              .issuedAt(now.minusSeconds(7200))
              .expiresAt(expired ? now.minusSeconds(3600) : now.plusSeconds(3600))
              .build();
      String token =
          encoder
              .encode(
                  JwtEncoderParameters.from(
                      JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeId()).build(),
                      claims))
              .getTokenValue();
      client()
          .get()
          .uri("/api/v1/health")
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }
  }

  @Test
  void publishedJwksContainsNoPrivateMaterial() {
    client()
        .get()
        .uri("/.well-known/jwks.json")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.keys[0].kid")
        .exists()
        .jsonPath("$.keys[0].n")
        .exists()
        .jsonPath("$.keys[0].d")
        .doesNotExist()
        .jsonPath("$.keys[0].p")
        .doesNotExist();
  }

  @Test
  void serviceJwksIsIndependentFromUserKeys() {
    client()
        .get()
        .uri("/.well-known/service-jwks.json")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.keys[0].kid")
        .isEqualTo("gateway-service-test")
        .jsonPath("$.keys[0].d")
        .doesNotExist()
        .jsonPath("$.keys[0].p")
        .doesNotExist();
  }

  @Test
  void retiredStreamsAndOpenApiCatalogDoNotReachOldCore() throws Exception {
    String token = login();
    for (String path :
        List.of("/api/v1/stream", "/api/v1/alarms/stream", "/api/v1/workorders/stream"))
      client()
          .get()
          .uri(path)
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isEqualTo(410);
    client()
        .get()
        .uri("/api/v1/openapi")
        .headers(h -> h.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.services.length()")
        .isEqualTo(5)
        .jsonPath("$.data.historicalOwners.workorders")
        .isEqualTo("workflow-service");
  }

  @Test
  void specificPathsResolveToTheirBusinessOwnerBeforeWildcards() {
    var expected =
        Map.of(
            "/api/v1/devices/device-1/maintenance-history", "workorders",
            "/api/v1/workorder-operations/operation-1", "workorders",
            "/api/v1/devices/device-1/status-history", "device-assets",
            "/api/v1/incidents/incident-1", "faults",
            "/api/v1/users/user-1", "foundation-api",
            "/api/v1/agent/conversations/conversation-1/messages", "agent-stream",
            "/api/v1/legacy/workorders/1", "historical-workorders");
    expected.forEach(
        (path, id) -> {
          var exchange =
              org.springframework.mock.web.server.MockServerWebExchange.from(
                  org.springframework.mock.http.server.reactive.MockServerHttpRequest.method(
                      path.endsWith("/messages")
                          ? org.springframework.http.HttpMethod.POST
                          : org.springframework.http.HttpMethod.GET,
                      path));
          var route =
              routes
                  .getRoutes()
                  .filterWhen(candidate -> Mono.from(candidate.getPredicate().apply(exchange)))
                  .next()
                  .block();
          Assertions.assertNotNull(route);
          Assertions.assertEquals(id, route.getId());
        });
  }

  @AfterAll
  static void stopDownstream() {
    DOWNSTREAM.disposeNow();
  }
}
