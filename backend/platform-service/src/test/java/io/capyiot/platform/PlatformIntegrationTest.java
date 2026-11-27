package io.capyiot.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(
    properties = {
      "capyiot.platform.clients.enabled=false",
      "server.ssl.enabled=false",
      "capyiot.platform.command-fingerprint-key=test-stable-fingerprint-key-at-least-32-characters"
    })
@org.springframework.context.annotation.Import(PlatformTestConfiguration.class)
@AutoConfigureMockMvc
class PlatformIntegrationTest {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");
  static final String PASSWORD = "test-password";
  static final String HASH =
      Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
  static final RSAKey SERVICE_RSA = rsa();
  static final io.capyiot.service.ServiceKeyRing SERVICE_RING = serviceRing();

  static io.capyiot.service.ServiceKeyRing serviceRing() {
    try {
      var file = java.nio.file.Files.createTempFile("platform-service-fixture", ".json");
      java.nio.file.Files.writeString(file, new JWKSet(SERVICE_RSA).toString(false));
      return new io.capyiot.service.ServiceKeyRing(file);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  static final RSAKey RSA = rsa();
  static final HttpServer JWKS = server();

  private static RSAKey rsa() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] body =
                new JWKSet(RSA.toPublicJWK())
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.start();
      return server;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add(
        "capyiot.foundation.jwks-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    r.add("capyiot.foundation.bootstrap.admin-hash", () -> HASH);
    r.add("capyiot.foundation.bootstrap.operator-hash", () -> HASH);
  }

  @Autowired PlatformService platform;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc http;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM command_request");
    db.update("DELETE FROM outbox_event");
    db.update("DELETE FROM audit_record");
    db.update("DELETE FROM login_session");
    db.update("DELETE FROM user_access_grant_area");
    db.update("DELETE FROM user_access_grant");
    db.update("DELETE FROM user_credential");
    db.update("DELETE FROM app_user");
    platform.seed(HASH, HASH);
  }

  Jwt login(String login) {
    var identity = platform.login(new PlatformService.LoginCommand(login, PASSWORD));
    var claims =
        JwtClaimsSet.builder()
            .issuer("capyiot-gateway")
            .subject(identity.userId())
            .audience(List.of("capyiot-api"))
            .id(identity.jti())
            .issuedAt(Instant.now())
            .expiresAt(identity.expiresAt())
            .claim("sid", identity.sessionId())
            .claim("authz_ver", identity.authorizationVersion())
            .build();
    String token =
        new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA)))
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA.getKeyID()).build(), claims))
            .getTokenValue();
    return Jwt.withTokenValue(token)
        .headers(h -> h.put("alg", "RS256"))
        .claims(c -> c.putAll(claims.getClaims()))
        .build();
  }

  @Test
  void realRs256RequestReturnsPersistedIdentity() throws Exception {
    Jwt token = login("operator");
    http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token.getTokenValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value("USER-OPERATOR"))
        .andExpect(jsonPath("$.data.roleCodes[0]").value("OPS_OPERATOR"));
    assertTrue(
        db.queryForObject(
                    "SELECT password_hash FROM user_credential WHERE user_id='USER-OPERATOR'",
                    String.class)
                .length()
            > 64);
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM login_session", Integer.class));
  }

  @Test
  void logoutIsIdempotentAndOldTokenImmediatelyFails() throws Exception {
    Jwt token = login("operator");
    for (int i = 0; i < 2; i++)
      http.perform(
              post("/api/v1/auth/logout")
                  .header("Authorization", "Bearer " + token.getTokenValue()))
          .andExpect(status().isOk());
    http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token.getTokenValue()))
        .andExpect(status().isUnauthorized());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_record WHERE action_code='auth.logout'", Integer.class));
  }

  @Test
  void sysAdminDoesNotImplicitlyReceiveBusinessPermissions() throws Exception {
    var admin = platform.current(login("admin"));
    assertThrows(ApiProblem.class, () -> admin.require("device.read"));
    assertThrows(ApiProblem.class, () -> admin.require("workorder.create"));
    assertEquals(5, platform.users(admin).size());
    Jwt operator = login("operator");
    http.perform(get("/api/v1/users").header("Authorization", "Bearer " + operator.getTokenValue()))
        .andExpect(status().isForbidden());
  }

  @Test
  void grantRevocationRevokesSessionsAndDoesNotReseed() throws Exception {
    var admin = platform.current(login("admin"));
    Jwt operator = login("operator");
    String grant =
        db.queryForObject(
            "SELECT grant_id FROM user_access_grant WHERE user_id='USER-OPERATOR'", String.class);
    platform.revokeGrant(admin, grant, "职责调整");
    http.perform(
            get("/api/v1/auth/me").header("Authorization", "Bearer " + operator.getTokenValue()))
        .andExpect(status().isUnauthorized());
    assertEquals(
        2L,
        db.queryForObject(
            "SELECT authorization_version FROM app_user WHERE user_id='USER-OPERATOR'",
            Long.class));
    platform.seed(HASH, HASH);
    assertTrue(platform.current(login("operator")).permissions().isEmpty());
  }

  @Test
  void disabledUserCannotLoginAndExistingSessionFails() throws Exception {
    var admin = platform.current(login("admin"));
    Jwt operator = login("operator");
    platform.updateUser(
        admin, "USER-OPERATOR", new PlatformService.UpdateUser(null, null, "DISABLED", 0L));
    assertThrows(
        ApiProblem.class,
        () -> platform.login(new PlatformService.LoginCommand("operator", PASSWORD)));
    http.perform(
            get("/api/v1/auth/me").header("Authorization", "Bearer " + operator.getTokenValue()))
        .andExpect(status().isUnauthorized());
    assertThrows(
        ApiProblem.class,
        () ->
            platform.updateUser(
                admin, "USER-OPERATOR", new PlatformService.UpdateUser(null, null, "ACTIVE", 0L)));
  }

  @Test
  void internalLoginRequiresBoundServiceIdentityAndRetiredRouteStaysClosed() throws Exception {
    String body = "{\"loginName\":\"admin\",\"password\":\"test-password\"}";
    http.perform(post("/internal/v1/identity/login").contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/internal/foundation/login")
                .header("X-Core-Service-Key", "retired-key")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    var budget =
        io.capyiot.service.CallBudget.start(
            java.time.Duration.ofSeconds(8), java.time.Clock.systemUTC());
    var signer =
        new io.capyiot.service.ServiceTokenSigner(
            io.capyiot.contract.internal.ServiceId.GATEWAY,
            SERVICE_RING,
            java.time.Clock.systemUTC());
    String token =
        signer.sign(
            io.capyiot.contract.internal.InternalEndpoint.PLATFORM_LOGIN,
            "/internal/v1/identity/login",
            null,
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            budget);
    http.perform(
            post("/internal/v1/identity/login")
                .header("X-Service-Authorization", "Bearer " + token)
                .header("X-CapyIoT-Deadline", budget.header())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value("USER-ADMIN"));
    http.perform(
            post("/internal/v1/identity/login")
                .header("X-Service-Authorization", "Bearer " + token)
                .header("X-CapyIoT-Deadline", budget.header())
                .contentType("application/json")
                .content(body + " "))
        .andExpect(status().isUnauthorized());
    assertFalse(db.queryForList("SELECT * FROM audit_record").toString().contains(PASSWORD));
  }

  @Test
  void repeatedBadPasswordsLockCredentialWithoutLosingFailureAudit() {
    for (int i = 0; i < 5; i++)
      assertThrows(
          ApiProblem.class,
          () -> platform.login(new PlatformService.LoginCommand("operator", "wrong")));
    assertNotNull(
        db.queryForObject(
            "SELECT locked_until FROM user_credential WHERE user_id='USER-OPERATOR'",
            java.sql.Timestamp.class));
    assertThrows(
        ApiProblem.class,
        () -> platform.login(new PlatformService.LoginCommand("operator", PASSWORD)));
    assertEquals(
        6,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_record WHERE result='DENIED'", Integer.class));
  }

  @Test
  void permissionsAndScopeComeFromSameGrant() {
    var context =
        new AuthorizationContext(
            "u",
            "u",
            "u",
            1,
            "s",
            List.of(
                new AuthorizationContext.Grant("a", "SYS_ADMIN", "NONE", null, List.of(), null),
                new AuthorizationContext.Grant(
                    "b", "OPS_OPERATOR", "PARK", "PARK-B", List.of(), null)));
    assertEquals("PARK-B", context.require("device.read").getFirst().parkId());
    assertThrows(ApiProblem.class, () -> context.require("device.manage"));
  }

  @Test
  void userCommandsReplayWithoutStoringCredentialsAndRejectConflictingKeys() throws Exception {
    Jwt admin = login("admin");
    String authorization = "Bearer " + admin.getTokenValue();
    String command =
        "{\"loginName\":\"new-user\",\"password\":\"private-test-password\",\"displayName\":\"New"
            + " user\"}";
    String first =
        http.perform(
                post("/api/v1/users")
                    .header("Authorization", authorization)
                    .header("Idempotency-Key", "create-user")
                    .contentType("application/json")
                    .content(command))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    http.perform(
            post("/api/v1/users")
                .header("Authorization", authorization)
                .header("Idempotency-Key", "create-user")
                .contentType("application/json")
                .content(command))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.userId")
                .value(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(first)
                        .path("data")
                        .path("userId")
                        .asText()))
        .andExpect(jsonPath("$.data.created").value(false));
    http.perform(
            post("/api/v1/users")
                .header("Authorization", authorization)
                .header("Idempotency-Key", "create-user")
                .contentType("application/json")
                .content(command.replace("new-user", "other-user")))
        .andExpect(status().isConflict());
    http.perform(
            post("/api/v1/users")
                .header("Authorization", authorization)
                .contentType("application/json")
                .content(command))
        .andExpect(status().isBadRequest());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE login_name='new-user'", Integer.class));
    assertFalse(
        db.queryForList("SELECT * FROM command_request")
            .toString()
            .contains("private-test-password"));
    assertFalse(
        db.queryForList("SELECT * FROM audit_record").toString().contains("private-test-password"));
  }

  @Test
  void simultaneousAuthorizationChecksDoNotExhaustTheConnectionPool() throws Exception {
    Jwt token = login("operator");
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(12)) {
      var requests =
          java.util.stream.IntStream.range(0, 12)
              .mapToObj(
                  index ->
                      workers.submit(
                          () -> {
                            start.await();
                            for (int i = 0; i < 3; i++)
                              assertEquals("USER-OPERATOR", platform.current(token).userId());
                            return true;
                          }))
              .toList();
      start.countDown();
      for (var request : requests)
        assertTrue(request.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @AfterAll
  static void close() {
    JWKS.stop(0);
  }

  private String service(
      io.capyiot.contract.internal.InternalEndpoint operation,
      io.capyiot.service.ActorCredential actor,
      byte[] body,
      io.capyiot.service.CallBudget budget)
      throws Exception {
    var file = java.nio.file.Files.createTempFile("capyiot-platform-test-", ".json");
    try {
      java.nio.file.Files.writeString(file, new JWKSet(SERVICE_RSA).toString(false));
      var signer =
          new io.capyiot.service.ServiceTokenSigner(
              io.capyiot.contract.internal.ServiceId.GATEWAY,
              new io.capyiot.service.ServiceKeyRing(file),
              java.time.Clock.systemUTC());
      return "Bearer " + signer.sign(operation, operation.path(), actor, body, budget);
    } finally {
      java.nio.file.Files.delete(file);
    }
  }

  @Test
  void newInternalContextRequiresSeparateServiceIdentityAndActorBinding() throws Exception {
    Jwt operator = login("operator"), admin = login("admin");
    String auth = "Bearer " + operator.getTokenValue();
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var budget =
        io.capyiot.service.CallBudget.start(
            java.time.Duration.ofSeconds(10), java.time.Clock.systemUTC());
    var endpoint = io.capyiot.contract.internal.InternalEndpoint.IDENTITY_CONTEXT;
    http.perform(
            post(endpoint.path())
                .header("Authorization", auth)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    String token = service(endpoint, io.capyiot.service.ActorCredential.bearer(auth), body, budget);
    http.perform(
            post(endpoint.path())
                .header("Authorization", auth)
                .header("X-Service-Authorization", token)
                .header("X-CapyIoT-Deadline", budget.header())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value("USER-OPERATOR"));
    http.perform(
            post(endpoint.path())
                .header("Authorization", "Bearer " + admin.getTokenValue())
                .header("X-Service-Authorization", token)
                .header("X-CapyIoT-Deadline", budget.header())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void newInternalLoginStillLetsGatewayOwnTokenIssuance() throws Exception {
    byte[] body =
        "{\"loginName\":\"operator\",\"password\":\"test-password\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var endpoint = io.capyiot.contract.internal.InternalEndpoint.PLATFORM_LOGIN;
    var budget =
        io.capyiot.service.CallBudget.start(
            java.time.Duration.ofSeconds(10), java.time.Clock.systemUTC());
    http.perform(
            post(endpoint.path())
                .header("X-Service-Authorization", service(endpoint, null, body, budget))
                .header("X-CapyIoT-Deadline", budget.header())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").isString())
        .andExpect(jsonPath("$.data.accessToken").doesNotExist());
  }

  @Test
  void platformFactsAreAtomicAndDoNotContainCommandCredentials() throws Exception {
    var admin = platform.current(login("admin"));
    platform.createUser(
        admin,
        new PlatformService.CreateUser(
            "outbox-user", "never-store-this-password", "Outbox user", null));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_type='platform.user.create'",
            Integer.class));
    assertFalse(
        db.queryForList("SELECT payload_json FROM outbox_event")
            .toString()
            .contains("never-store-this-password"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_record WHERE action_code='platform.user.create'",
            Integer.class));
  }

  private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> event(String eventId)
      throws Exception {
    var value =
        Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry("eventId", eventId),
            Map.entry("eventType", "DeviceConnectionLost"),
            Map.entry("sourceContext", "device"),
            Map.entry("producer", "device-service"),
            Map.entry("aggregateType", "DEVICE"),
            Map.entry("aggregateId", "IPC-1"),
            Map.entry("aggregateVersion", 1),
            Map.entry("parkId", "CAMPUS-A"),
            Map.entry("actor", Map.of("serviceId", "device-service")),
            Map.entry("deviceIds", List.of("IPC-1")),
            Map.entry("body", Map.of("status", "OFFLINE")));
    return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
        "capyiot.domain.device.v1",
        0,
        0,
        "IPC-1",
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value));
  }

  @Test
  void domainAuditReplayIsDeduplicatedByExistingInboxIdentity() throws Exception {
    db.update("DELETE FROM audit_event_inbox");
    String id = UUID.randomUUID().toString();
    var projection =
        new DomainAuditProjection(
            db,
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    db.getDataSource())),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            false);
    projection.consume(event(id));
    projection.consume(event(id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_record WHERE source_event_id=?", Integer.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_event_inbox WHERE event_id=?", Integer.class, id));
  }

  @Test
  void failedAuditInsertRollsBackInboxReceipt() throws Exception {
    db.update("DELETE FROM audit_event_inbox");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " audit_record(actor_type,actor_id,action_code,resource_type,resource_id,result,source,source_event_id)"
            + " VALUES('SERVICE','device','existing','DEVICE','IPC-1','SUCCESS','device',?)",
        id);
    var projection =
        new DomainAuditProjection(
            db,
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    db.getDataSource())),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            false);
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class, () -> projection.consume(event(id)));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_event_inbox WHERE event_id=?", Integer.class, id));
  }
}
