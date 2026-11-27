package io.capyiot.device.video;

import static io.capyiot.device.video.AssetModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.capyiot.contract.internal.*;
import io.capyiot.service.ServiceFailure;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    classes = io.capyiot.device.DeviceServiceApplication.class,
    properties = {
      "capyiot.device.clients.enabled=false",
      "server.ssl.enabled=false",
      "capyiot.domain-runtime.enabled=false",
      "capyiot.device-runtime.enabled=false"
    })
@Import(DeviceAssetIntegrationTest.Dependencies.class)
@AutoConfigureMockMvc
@Testcontainers
class DeviceAssetIntegrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.11")
          .withDatabaseName("device_test")
          .withUsername("root")
          .withPassword("test");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) throws Exception {
    var create =
        MYSQL.execInContainer(
            "mysql",
            "-uroot",
            "-ptest",
            "-e",
            "CREATE DATABASE IF NOT EXISTS platform_test; CREATE TABLE IF NOT EXISTS"
                + " platform_test.app_user(user_id VARCHAR(64)); CREATE USER IF NOT EXISTS"
                + " 'device_test_app'@'%' IDENTIFIED BY 'test'; GRANT ALL ON device_test.* TO"
                + " 'device_test_app'@'%';");
    if (create.getExitCode() != 0) throw new IllegalStateException(create.getStderr());
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", () -> "device_test_app");
    r.add("spring.datasource.password", () -> "test");
    r.add(
        "capyiot.device.command-fingerprint-key",
        () -> "fixture-stable-fingerprint-key-over-32-characters");
    r.add(
        "capyiot.device-key-file",
        () ->
            System.getProperty("java.io.tmpdir")
                + "/capyiot-native-device-test-"
                + UUID.randomUUID()
                + ".json");
    r.add("capyiot.device-keys-generate", () -> true);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    DeviceIdentity identities() {
      return token ->
          new AuthorizationContext(
              token.getSubject(),
              "fixture",
              "fixture",
              1,
              "fixture-session",
              List.of(
                  new AuthorizationContext.Grant(
                      "g", "OPS_OPERATOR", "AREA_SUBTREE", "CAMPUS-A", List.of("AREA-1-1"), null)));
    }

    @Bean
    JwtDecoder tokens() {
      return token -> {
        throw new org.springframework.security.oauth2.jwt.BadJwtException(
            "Use explicit MockMvc JWT fixture");
      };
    }

    @Bean
    HealthQueryPort health() {
      return ids -> {
        throw ServiceFailure.unavailable("HEALTH_UNAVAILABLE", ServiceId.FAULT, true);
      };
    }

    @Bean
    AssignmentQueryPort assignments() {
      return (user, park, relation) -> {
        throw ServiceFailure.unavailable(
            "ASSIGNMENT_AUTHORITY_UNAVAILABLE", ServiceId.WORKFLOW, true);
      };
    }
  }

  @Autowired AssetService assets;
  @Autowired AssetStore store;
  @Autowired DeviceSessions sessions;
  @Autowired DeviceStateService states;
  @Autowired DeviceSigningKeys signing;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired MockMvc http;
  @Autowired DeviceInternalController internal;
  @Autowired HistoricalDeviceController historical;
  AuthorizationContext supervisor;

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "device_fault_position",
            "device_authentication_attempt",
            "broker_disconnect_outbox",
            "monitoring_point_binding",
            "network_dependency",
            "power_dependency",
            "video_channel_association",
            "monitoring_point",
            "device_event_receipt",
            "device_stream_cursor",
            "device_status_history",
            "device_runtime_state",
            "network_link",
            "device_state_history",
            "device_state_snapshot",
            "device_event",
            "device_session",
            "device",
            "command_request",
            "outbox_event")) store.db.update("DELETE FROM " + table);
    store.db.update("INSERT IGNORE INTO campus(id,name) VALUES('PARK-B','B')");
    store.db.update("INSERT IGNORE INTO park(park_id,park_code,name) VALUES('PARK-B','B','B')");
    store.db.update(
        "INSERT IGNORE INTO area(id,park_id,area_type,area_code,name)"
            + " VALUES('AREA-B','PARK-B','AREA','B','B')");
    store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES('PARK-B')");
    supervisor = actor("OPS_SUPERVISOR", "PARK", "CAMPUS-A", List.of());
  }

  AuthorizationContext actor(String role, String scope, String park, List<String> roots) {
    return new AuthorizationContext(
        "fixture-user",
        "fixture",
        "fixture",
        1,
        "fixture-session",
        List.of(new AuthorizationContext.Grant("g", role, scope, park, roots, null)));
  }

  JsonNode create(String code, DeviceType type, String area, Map<String, Boolean> caps) {
    var owner =
        area.equals("AREA-B") ? actor("OPS_SUPERVISOR", "PARK", "PARK-B", List.of()) : supervisor;
    return assets.createDevice(
        owner,
        UUID.randomUUID().toString(),
        new CreateDevice(code, type, code, area, null, null, null, null, null, caps));
  }

  String device(String code, DeviceType type) {
    return create(code, type, "AREA-1-1", Map.of()).path("device").path("deviceId").asText();
  }

  String point(String code) {
    return assets
        .createPoint(
            supervisor, UUID.randomUUID().toString(), new CreatePoint(code, code, "AREA-1-1", null))
        .path("monitoringPointId")
        .asText();
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  @Test
  void newCanonicalLocationTreeDoesNotInventLegacyForeignKeys() {
    String suffix = UUID.randomUUID().toString();
    String building =
        assets
            .createArea(
                supervisor, key(), new CreateArea(null, AreaType.BUILDING, "B-" + suffix, "新楼栋"))
            .path("areaId")
            .asText();
    String floor =
        assets
            .createArea(
                supervisor, key(), new CreateArea(building, AreaType.FLOOR, "F-" + suffix, "新楼层"))
            .path("areaId")
            .asText();
    String area =
        assets
            .createArea(
                supervisor, key(), new CreateArea(floor, AreaType.AREA, "A-" + suffix, "新区域"))
            .path("areaId")
            .asText();
    String id =
        create("IPC-NEW", DeviceType.IPC, area, Map.of()).path("device").path("deviceId").asText();
    var stored =
        store.db.queryForMap("SELECT area_id,building_id,floor_id FROM device WHERE id=?", id);
    assertEquals(area, stored.get("area_id"));
    assertNull(stored.get("building_id"));
    assertNull(stored.get("floor_id"));
  }

  @Test
  void registrationHasUnknownStateAndOneTimeCredentialWithIdempotentReplay() {
    var c =
        new CreateDevice(
            "IPC-1", DeviceType.IPC, "IPC", "AREA-1-1", null, null, null, null, null, Map.of());
    var first = assets.createDevice(supervisor, "register-1", c);
    String id = first.path("device").path("deviceId").asText(),
        secret = first.path("deviceSecret").asText();
    assertEquals("UNKNOWN", first.path("device").path("connectivityStatus").asText());
    assertEquals("NO_DATA", first.path("device").path("dataFreshness").asText());
    assertEquals("UNKNOWN", first.path("device").path("healthStatus").asText());
    assertTrue(
        Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
            .matches(
                secret,
                store.db.queryForObject(
                    "SELECT device_secret_hash FROM device WHERE id=?", String.class, id)));
    var replay = assets.createDevice(supervisor, "register-1", c);
    assertEquals(id, replay.path("device").path("deviceId").asText());
    assertFalse(replay.has("deviceSecret"));
    assertFalse(
        store
            .db
            .queryForList("SELECT result_snapshot FROM command_request")
            .toString()
            .contains(secret));
    assertFalse(
        store.db.queryForList("SELECT payload_json FROM outbox_event").toString().contains(secret));
    assertThrows(
        ApiProblem.class,
        () ->
            assets.createDevice(
                supervisor,
                "register-1",
                new CreateDevice(
                    "OTHER",
                    DeviceType.IPC,
                    "IPC",
                    "AREA-1-1",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM device", Integer.class));
  }

  @Test
  void pointIdentitySurvivesReplacementAndBindingIsUniqueInBothDirections() {
    String first = device("IPC-1", DeviceType.IPC),
        second = device("IPC-2", DeviceType.IPC),
        point = point("POINT-1"),
        other = point("POINT-2");
    assets.bind(supervisor, key(), point, new BindPoint(first, "安装", 0L, 0L));
    assertThrows(
        ApiProblem.class,
        () -> assets.bind(supervisor, key(), other, new BindPoint(first, "重复", 0L, 1L)));
    assets.bind(supervisor, key(), point, new BindPoint(second, "换机", 1L, 0L));
    var history = assets.bindings(supervisor, null, point);
    assertEquals(2, history.size());
    assertNotNull(history.getFirst().get("validTo"));
    assertNull(history.getLast().get("validTo"));
    assertEquals(point, history.getFirst().get("monitoringPointId"));
  }

  @Test
  void networkCyclesParentsAndPortsAreConstrained() {
    String sw1 = device("SW-1", DeviceType.SWITCH),
        sw2 = device("SW-2", DeviceType.SWITCH),
        ipc = device("IPC-1", DeviceType.IPC),
        other = device("IPC-2", DeviceType.IPC);
    assets.network(supervisor, key(), new Network(sw1, sw2, "p1", null, "接线"));
    assertThrows(
        ApiProblem.class,
        () -> assets.network(supervisor, key(), new Network(sw2, sw1, "p2", null, "环路")));
    assets.network(supervisor, key(), new Network(sw1, ipc, "p2", null, "接线"));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> assets.network(supervisor, key(), new Network(sw2, ipc, "p1", null, "重复上游")));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> assets.network(supervisor, key(), new Network(sw1, other, "p2", null, "端口占用")));
  }

  @Test
  void concurrentParentsProduceOnlyOneCurrentNetworkRelation() throws Exception {
    String sw1 = device("SW-1", DeviceType.SWITCH),
        sw2 = device("SW-2", DeviceType.SWITCH),
        ipc = device("IPC-1", DeviceType.IPC);
    var start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.of(sw1, sw2).stream()
              .map(
                  sw ->
                      workers.submit(
                          () -> {
                            try {
                              start.await();
                              assets.network(
                                  supervisor, key(), new Network(sw, ipc, "p1", null, "并发接线"));
                              success.incrementAndGet();
                            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          }))
              .toList();
      start.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, success.get());
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM network_dependency WHERE valid_to IS NULL", Integer.class));
  }

  @Test
  void poeRequiresCapabilitiesAndRemainsIndependentFromNetwork() {
    var switchResult = create("SW-POE", DeviceType.SWITCH, "AREA-1-1", Map.of("poeOutput", true));
    var ipcResult = create("IPC-POE", DeviceType.IPC, "AREA-1-1", Map.of("poeInput", true));
    assertTrue(switchResult.path("device").has("deviceId"), switchResult::toString);
    assertTrue(ipcResult.path("device").has("deviceId"), ipcResult::toString);
    String sw = switchResult.path("device").path("deviceId").asText(),
        ipc = ipcResult.path("device").path("deviceId").asText();
    assertTrue(
        assets.visibleDeviceIds(supervisor, "topology.manage").containsAll(List.of(sw, ipc)),
        () ->
            "created="
                + sw
                + ","
                + ipc
                + " rows="
                + store.db.queryForList("SELECT id,device_type,legacy_only,campus_id FROM device"));
    assets.power(supervisor, key(), new Power(sw, "poe1", ipc, "供电接线"));
    assertEquals(
        0, store.db.queryForObject("SELECT COUNT(*) FROM network_dependency", Integer.class));
    String noPoe = device("IPC-PLAIN", DeviceType.IPC);
    assertThrows(
        ApiProblem.class,
        () -> assets.power(supervisor, key(), new Power(sw, "poe2", noPoe, "能力不足")));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> assets.power(supervisor, key(), new Power(sw, "poe3", ipc, "重复供电")));
  }

  @Test
  void nvrChannelsAreUniqueAndCannotCrossParks() {
    String nvr = device("NVR-1", DeviceType.NVR),
        ipc1 = device("IPC-1", DeviceType.IPC),
        ipc2 = device("IPC-2", DeviceType.IPC);
    assets.video(supervisor, key(), new Video(nvr, ipc1, 1, "通道关联"));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> assets.video(supervisor, key(), new Video(nvr, ipc2, 1, "通道占用")));
    String foreign =
        create("IPC-B", DeviceType.IPC, "AREA-B", Map.of())
            .path("device")
            .path("deviceId")
            .asText();
    assertEquals(
        404,
        assertThrows(
                ApiProblem.class,
                () -> assets.video(supervisor, key(), new Video(nvr, foreign, 2, "跨园区")))
            .status());
  }

  @Test
  void scopedListsCountOnlyVisibleObjectsAndTopologyNeverLeaksHiddenIdentifiers() {
    String ipc = device("IPC-1", DeviceType.IPC);
    String hidden =
        create("SW-HIDDEN", DeviceType.SWITCH, "AREA-1-2", Map.of())
            .path("device")
            .path("deviceId")
            .asText();
    assets.network(supervisor, key(), new Network(hidden, ipc, "p1", null, "跨区域连接"));
    var scoped = actor("OPS_OPERATOR", "AREA_SUBTREE", "CAMPUS-A", List.of("AREA-1-1"));
    var page =
        assets.devices(scoped, null, null, "SUBTREE", null, null, null, null, null, null, 1, 20);
    assertEquals(1, page.total());
    assertEquals(404, assertThrows(ApiProblem.class, () -> assets.detail(scoped, hidden)).status());
    var graph = assets.topology(scoped, ipc, null, Instant.now(), 2);
    assertEquals("INCOMPLETE", graph.get("completeness"));
    assertFalse(graph.toString().contains(hidden));
    assertFalse(graph.toString().contains("SW-HIDDEN"));
  }

  @Test
  void retiringDeviceEndsRelationsAndPreservesHistory() {
    String sw = device("SW-1", DeviceType.SWITCH),
        ipc = device("IPC-1", DeviceType.IPC),
        point = point("POINT-1");
    assets.network(supervisor, key(), new Network(sw, ipc, "p1", null, "安装"));
    assets.bind(supervisor, key(), point, new BindPoint(ipc, "安装", 0L, 0L));
    assets.updateDevice(
        supervisor,
        key(),
        ipc,
        new UpdateDevice(null, null, null, null, null, null, null, null, AssetStatus.RETIRED, 1L));
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM network_dependency WHERE valid_to IS NULL", Integer.class));
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM network_dependency", Integer.class));
    assertNotNull(assets.bindings(supervisor, null, point).getFirst().get("validTo"));
    assertThrows(
        ApiProblem.class,
        () -> assets.bind(supervisor, key(), point, new BindPoint(ipc, "恢复", 1L, 2L)));
  }

  @Test
  void restCommandsRequireCurrentPermissionIdempotencyAndValidDto() throws Exception {
    Jwt token =
        Jwt.withTokenValue("authentication-fixture")
            .header("alg", "RS256")
            .subject("operator")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    http.perform(
            post("/api/v1/devices")
                .with(jwt().jwt(token))
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(
                    "{\"assetCode\":\"IPC-1\",\"deviceType\":\"IPC\",\"name\":\"IPC\",\"areaId\":\"AREA-1-1\"}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/api/v1/devices")
                .with(jwt().jwt(token))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/v1/devices").with(jwt().jwt(token)).param("deviceType", "SENSOR"))
        .andExpect(status().isBadRequest());
    http.perform(
            get("/api/v1/devices")
                .with(jwt().jwt(token))
                .param("actorUserId", "USER-ADMIN")
                .param("parkId", "PARK-B"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  void independentAssetCredentialsCannotReadPlatformSchema() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.db.queryForList("SELECT * FROM platform_test.app_user"));
  }

  JsonNode activeDevice(String code) {
    var result = create(code, DeviceType.IPC, "AREA-1-1", Map.of());
    String id = result.path("device").path("deviceId").asText();
    assets.updateDevice(
        supervisor,
        key(),
        id,
        new UpdateDevice(
            null, null, null, null, null, null, null, null, AssetStatus.IN_SERVICE, 0L));
    return result;
  }

  io.capyiot.contract.VideoDeviceEvent event(
      String device,
      DeviceSessions.SessionResult session,
      String sid,
      String type,
      String stream,
      long sequence,
      Instant received,
      Map<String, Object> payload) {
    return new io.capyiot.contract.VideoDeviceEvent(
        key(),
        1,
        device,
        sid,
        type,
        stream,
        sequence,
        received.minusSeconds(2),
        json.valueToTree(payload),
        "CAMPUS-A",
        "IPC",
        "SIMULATOR",
        session.sessionEpoch(),
        session.credentialVersion(),
        received,
        key(),
        "device-ingestion");
  }

  @Test
  void independentStreamsRejectDuplicatesAndOldSequencesWithoutExtendingHeartbeat() {
    var d = activeDevice("IPC-STREAM");
    String id = d.path("device").path("deviceId").asText();
    var session =
        sessions.create(
            new DeviceSessions.SessionCommand(id, d.path("deviceSecret").asText(), "boot-1"));
    Instant at = Instant.now();
    var hb =
        event(id, session, "boot-1", "HEARTBEAT", "HEARTBEAT", 100, at, Map.of("bootId", "boot-1"));
    assertEquals("APPLIED", states.accept(hb, 0, 1));
    assertEquals("DUPLICATE", states.accept(hb, 0, 1));
    var older =
        event(
            id,
            session,
            "boot-1",
            "HEARTBEAT",
            "HEARTBEAT",
            99,
            at.plusSeconds(30),
            Map.of("bootId", "boot-1"));
    assertEquals("IGNORED_STALE", states.accept(older, 0, 2));
    var mode =
        event(
            id,
            session,
            "boot-1",
            "STATUS_OBSERVATION",
            "STATUS/DEVICE/device/OPERATING_MODE",
            1,
            at.plusSeconds(1),
            Map.of(
                "resourceType",
                "DEVICE",
                "resourceKey",
                "device",
                "observationType",
                "OPERATING_MODE",
                "value",
                "LOW_POWER",
                "quality",
                "GOOD"));
    assertEquals("APPLIED", states.accept(mode, 0, 3));
    var state = store.db.queryForMap("SELECT * FROM device_runtime_state WHERE device_id=?", id);
    assertEquals("LOW_POWER", state.get("operating_mode"));
    assertEquals("FRESH", state.get("data_freshness"));
    assertEquals(1L, ((Number) state.get("heartbeat_generation")).longValue());
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM device_event", Integer.class));
    assertEquals(1, states.expire(at.plusSeconds(46), 45, 120));
    assertEquals(
        "OFFLINE",
        store.db.queryForObject(
            "SELECT connectivity_status FROM device_runtime_state WHERE device_id=?",
            String.class,
            id));
    var history = assets.history(supervisor, id, null, null, null, 1, 100);
    assertTrue(history.items().stream().anyMatch(h -> h.get("newValue").equals("LOW_POWER")));
  }

  @Test
  void committedDeviceFactSurvivesAConsumerExitBeforeOffsetCommit() throws Exception {
    try (var broker = new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.1.1")) {
      broker.start();
      String topic = "device-crash-boundary", group = "device-crash-" + key();
      var d = activeDevice("IPC-CONSUMER-CRASH");
      String id = d.path("device").path("deviceId").asText();
      var session =
          sessions.create(
              new DeviceSessions.SessionCommand(id, d.path("deviceSecret").asText(), "crash-boot"));
      var event =
          event(
              id,
              session,
              "crash-boot",
              "HEARTBEAT",
              "HEARTBEAT",
              1,
              Instant.now(),
              Map.of("bootId", "crash-boot"));
      var producerConfig =
          Map.<String, Object>of(
              "bootstrap.servers",
              broker.getBootstrapServers(),
              "key.serializer",
              org.apache.kafka.common.serialization.StringSerializer.class,
              "value.serializer",
              org.apache.kafka.common.serialization.StringSerializer.class,
              "acks",
              "all");
      try (var producer =
          new org.apache.kafka.clients.producer.KafkaProducer<String, String>(producerConfig)) {
        producer
            .send(
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                    topic, id, json.writeValueAsString(event)))
            .get(20, TimeUnit.SECONDS);
      }
      var config =
          Map.<String, Object>of(
              "bootstrap.servers",
              broker.getBootstrapServers(),
              "group.id",
              group,
              "enable.auto.commit",
              false,
              "auto.offset.reset",
              "earliest",
              "key.deserializer",
              org.apache.kafka.common.serialization.StringDeserializer.class,
              "value.deserializer",
              org.apache.kafka.common.serialization.StringDeserializer.class);
      long firstOffset = -1;
      int outboxCount = -1;
      for (int launch = 0; launch < 2; launch++) {
        try (var consumer =
            new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(config)) {
          consumer.subscribe(List.of(topic));
          org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = null;
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
          while (record == null && System.nanoTime() < deadline) {
            var records = consumer.poll(java.time.Duration.ofMillis(250));
            if (!records.isEmpty()) record = records.iterator().next();
          }
          assertNotNull(record, "Uncommitted offset must be delivered after restart");
          var parsed = json.readValue(record.value(), io.capyiot.contract.VideoDeviceEvent.class);
          assertEquals(
              launch == 0 ? "APPLIED" : "DUPLICATE",
              states.accept(parsed, record.partition(), record.offset()));
          if (launch == 0) {
            firstOffset = record.offset();
            outboxCount =
                store.db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
            assertNull(
                consumer
                    .committed(
                        Set.of(
                            new org.apache.kafka.common.TopicPartition(topic, record.partition())))
                    .get(new org.apache.kafka.common.TopicPartition(topic, record.partition())));
            // Exit here after the MySQL transaction has committed, without commitSync.
          } else {
            assertEquals(firstOffset, record.offset());
            assertEquals(
                outboxCount,
                store.db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
            consumer.commitSync();
          }
        }
      }
      assertEquals(
          1,
          store.db.queryForObject(
              "SELECT COUNT(*) FROM device_event_receipt WHERE event_id=?",
              Integer.class,
              event.eventId()));
      assertEquals(
          1L,
          store.db.queryForObject(
              "SELECT heartbeat_generation FROM device_runtime_state WHERE device_id=?",
              Long.class,
              id));
    }
  }

  @Test
  void sentBeforeCrashIsReclaimedWithTheSameEventIdentity() {
    var dataSource = java.util.Objects.requireNonNull(store.db.getDataSource());
    var crash = new java.util.concurrent.atomic.AtomicBoolean(true);
    var crashAfterSend =
        new org.springframework.jdbc.core.JdbcTemplate(dataSource) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.contains("status='SENT'") && crash.getAndSet(false))
              throw new AssertionError("simulated process exit after broker acknowledgement");
            return super.update(sql, args);
          }
        };
    @SuppressWarnings("unchecked")
    org.springframework.kafka.core.KafkaTemplate<String, String> kafka =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
    org.mockito.Mockito.when(
            kafka.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    var relay =
        new io.capyiot.outbox.OutboxRelay(
            crashAfterSend,
            kafka,
            json,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
            false,
            8);
    String eventId = UUID.randomUUID().toString();
    store.tx.executeWithoutResult(
        tx ->
            relay.enqueue(
                "CAMPUS-A",
                "DEVICE",
                "fixture-device",
                "DeviceRegistered",
                eventId,
                "fixture-domain-topic",
                Map.of("eventId", eventId)));
    assertThrows(AssertionError.class, relay::dispatch);
    assertEquals(
        "PENDING", store.db.queryForObject("SELECT status FROM outbox_event", String.class));
    assertNotNull(store.db.queryForObject("SELECT delivery_token FROM outbox_event", String.class));
    // Advance the durable lease, rather than sleeping a minute in the test.
    store.db.update(
        "UPDATE outbox_event SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
    var restarted =
        new io.capyiot.outbox.OutboxRelay(
            store.db,
            kafka,
            json,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
            false,
            8);
    restarted.dispatch();
    assertEquals("SENT", store.db.queryForObject("SELECT status FROM outbox_event", String.class));
    var payloads = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2))
        .send(
            org.mockito.ArgumentMatchers.eq("fixture-domain-topic"),
            org.mockito.ArgumentMatchers.eq("fixture-device"),
            payloads.capture());
    assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1));
    assertTrue(payloads.getValue().contains(eventId));
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
  }

  @Test
  void failedRejectionPublishMustEscapeSoTheOffsetCannotBeAcknowledged() throws Exception {
    @SuppressWarnings("unchecked")
    org.springframework.kafka.core.KafkaTemplate<String, String> kafka =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
    org.mockito.Mockito.when(
            kafka.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(
                new java.io.IOException("broker unavailable")))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    var runtime =
        new DeviceStateRuntime(
            states,
            json,
            kafka,
            45,
            120,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    var record =
        new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>(
            "capyiot.device.status.v1", 0, 19, "fixture-device", "invalid-json");
    assertThrows(java.util.concurrent.ExecutionException.class, () -> runtime.consume(record));
    runtime.consume(record);
    org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2))
        .send(
            org.mockito.ArgumentMatchers.eq("capyiot.device.rejected.v1"),
            org.mockito.ArgumentMatchers.eq("fixture-device"),
            org.mockito.ArgumentMatchers.anyString());
    assertEquals(
        0, store.db.queryForObject("SELECT COUNT(*) FROM device_event_receipt", Integer.class));
  }

  @Test
  void tokenClaimsSessionRenewalRotationAndOldEpochRejection() throws Exception {
    var d = activeDevice("IPC-SESSION");
    String id = d.path("device").path("deviceId").asText(),
        secret = d.path("deviceSecret").asText();
    var first = sessions.create(new DeviceSessions.SessionCommand(id, secret, "boot-1"));
    var renewed = sessions.create(new DeviceSessions.SessionCommand(id, secret, "boot-1"));
    assertEquals(first.sessionEpoch(), renewed.sessionEpoch());
    var jwt = com.nimbusds.jwt.SignedJWT.parse(first.sessionToken());
    assertTrue(
        jwt.verify(
            new com.nimbusds.jose.crypto.RSASSAVerifier(
                (com.nimbusds.jose.jwk.RSAKey) signing.keys().getKeys().getFirst())));
    assertEquals(List.of("capyiot-emqx"), jwt.getJWTClaimsSet().getAudience());
    assertEquals(id, jwt.getJWTClaimsSet().getSubject());
    assertEquals("capyiot-emqx", jwt.getPayload().toJSONObject().get("aud"));
    assertEquals("boot-1", jwt.getJWTClaimsSet().getStringClaim("sid"));
    long lifetime =
        java.time.Duration.between(
                jwt.getJWTClaimsSet().getIssueTime().toInstant(),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant())
            .toSeconds();
    assertTrue(lifetime >= 899 && lifetime <= 900);
    assertTrue(
        jwt.getJWTClaimsSet().getClaim("acl").toString().contains("/sessions/boot-1/events"));
    var next = sessions.create(new DeviceSessions.SessionCommand(id, secret, "boot-2"));
    assertEquals(first.sessionEpoch() + 1, next.sessionEpoch());
    assertThrows(ApiProblem.class, () -> sessions.current(id, "boot-1"));
    assertThrows(
        ApiProblem.class,
        () -> sessions.create(new DeviceSessions.SessionCommand(id, secret, "boot-1")));
    assertThrows(
        ApiProblem.class,
        () ->
            states.accept(
                event(
                    id,
                    first,
                    "boot-1",
                    "HEARTBEAT",
                    "HEARTBEAT",
                    1,
                    Instant.now(),
                    Map.of("bootId", "boot-1")),
                0,
                1));
    var rotated =
        sessions.rotate(supervisor, "rotate-1", id, new DeviceSessions.Rotate(1L, "密钥轮换"));
    assertTrue(rotated.has("deviceSecret"));
    assertFalse(
        sessions
            .rotate(supervisor, "rotate-1", id, new DeviceSessions.Rotate(1L, "密钥轮换"))
            .has("deviceSecret"));
    assertThrows(ApiProblem.class, () -> sessions.current(id, "boot-2"));
    assertThrows(
        ApiProblem.class,
        () -> sessions.create(new DeviceSessions.SessionCommand(id, secret, "boot-3")));
    assertFalse(
        store
            .db
            .queryForList("SELECT * FROM device_session")
            .toString()
            .contains(first.sessionToken()));
  }

  @Test
  void uncertainModeDoesNotOverwriteAndHeartbeatDoesNotRefreshBusinessFreshness() {
    var d = activeDevice("IPC-QUALITY");
    String id = d.path("device").path("deviceId").asText();
    var session =
        sessions.create(
            new DeviceSessions.SessionCommand(id, d.path("deviceSecret").asText(), "boot"));
    Instant at = Instant.now();
    states.accept(
        event(
            id,
            session,
            "boot",
            "STATUS_OBSERVATION",
            "STATUS/DEVICE/device/OPERATING_MODE",
            1,
            at,
            Map.of(
                "resourceType",
                "DEVICE",
                "resourceKey",
                "device",
                "observationType",
                "OPERATING_MODE",
                "value",
                "LOW_POWER",
                "quality",
                "BAD")),
        0,
        1);
    assertEquals(
        "UNKNOWN",
        store.db.queryForObject(
            "SELECT operating_mode FROM device_runtime_state WHERE device_id=?", String.class, id));
    states.accept(
        event(
            id,
            session,
            "boot",
            "HEARTBEAT",
            "HEARTBEAT",
            1,
            at.plusSeconds(150),
            Map.of("bootId", "boot")),
        0,
        2);
    states.expire(at.plusSeconds(151), 45, 120);
    var s =
        store.db.queryForMap(
            "SELECT connectivity_status,data_freshness FROM device_runtime_state WHERE device_id=?",
            id);
    assertEquals("ONLINE", s.get("connectivity_status"));
    assertEquals("STALE", s.get("data_freshness"));
  }

  @Test
  void faultWatermarksIncludePendingOutboxFactsAndIgnoreHeartbeatOnlyVersions() {
    var d = activeDevice("IPC-POSITION");
    String id = d.path("device").path("deviceId").asText();
    var session =
        sessions.create(
            new DeviceSessions.SessionCommand(id, d.path("deviceSecret").asText(), "boot"));
    Instant at = Instant.now();
    states.accept(
        event(id, session, "boot", "HEARTBEAT", "HEARTBEAT", 1, at, Map.of("bootId", "boot")),
        0,
        1);
    var before = internal.runtime(new DeviceApi.Batch(List.of(id)));
    assertTrue(before.complete());
    assertEquals(1, before.devices().getFirst().requiredFaultPositions().size());
    var connection = before.devices().getFirst().requiredFaultPositions().getFirst();
    assertEquals("CONNECTIVITY", connection.streamKey());
    states.accept(
        event(
            id,
            session,
            "boot",
            "HEARTBEAT",
            "HEARTBEAT",
            2,
            at.plusSeconds(1),
            Map.of("bootId", "boot")),
        0,
        2);
    assertEquals(
        connection,
        internal
            .runtime(new DeviceApi.Batch(List.of(id)))
            .devices()
            .getFirst()
            .requiredFaultPositions()
            .getFirst());
    states.accept(
        event(
            id,
            session,
            "boot",
            "STATUS_OBSERVATION",
            "STATUS/DEVICE/device/OPERATING_MODE",
            1,
            at.plusSeconds(2),
            Map.of(
                "resourceType",
                "DEVICE",
                "resourceKey",
                "device",
                "observationType",
                "OPERATING_MODE",
                "value",
                "NORMAL",
                "quality",
                "GOOD")),
        0,
        3);
    var positions =
        internal
            .runtime(new DeviceApi.Batch(List.of(id)))
            .devices()
            .getFirst()
            .requiredFaultPositions();
    assertEquals(2, positions.size());
    for (var position : positions) {
      var outbox =
          json.valueToTree(
              store.db.queryForMap(
                  "SELECT status,payload_json FROM outbox_event WHERE event_id=?",
                  position.eventId()));
      assertEquals("PENDING", outbox.path("status").asText());
    }
    states.expire(at.plusSeconds(47), 45, 120);
    var lost =
        internal
            .runtime(new DeviceApi.Batch(List.of(id)))
            .devices()
            .getFirst()
            .requiredFaultPositions()
            .stream()
            .filter(p -> p.streamKey().equals("CONNECTIVITY"))
            .findFirst()
            .orElseThrow();
    assertNotEquals(connection.eventId(), lost.eventId());
    assertTrue(lost.sequence() > connection.sequence());
  }

  @Test
  void healthOutageIsExplicitAndDoesNotBlockAssetWrites() {
    String id = device("IPC-HEALTH", DeviceType.IPC);
    var detail = assets.detail(supervisor, id);
    assertEquals("UNKNOWN", detail.get("healthStatus"));
    assertEquals("UNAVAILABLE", detail.get("healthAvailability"));
    var overview = assets.overview(supervisor);
    assertNull(overview.get("abnormal"));
    assertEquals("UNAVAILABLE", overview.get("healthAvailability"));
    assertThrows(
        ServiceFailure.class,
        () ->
            assets.visibleDeviceIds(
                new AuthorizationContext(
                    "engineer",
                    "fixture",
                    "fixture",
                    1,
                    "session",
                    List.of(
                        new AuthorizationContext.Grant(
                            "g",
                            "REPAIR_TECH",
                            "ASSIGNED_TO_ME",
                            "CAMPUS-A",
                            List.of(),
                            "REPAIRER"))),
                "device.read"));
  }

  @Test
  void confirmedAgentCanOnlyPreflightTheCreateWorkOrderScope() {
    String id = device("IPC-DELEGATED", DeviceType.IPC);
    var delegated =
        new AuthorizationContext(
            supervisor.userId(),
            supervisor.loginName(),
            supervisor.displayName(),
            1,
            "delegation:confirmed",
            supervisor.grants());
    assertEquals(
        id,
        internal
            .scope(delegated, new DeviceApi.ScopeRequest("workorder.create", List.of(id)))
            .devices()
            .getFirst()
            .deviceId());
    assertEquals(
        403,
        assertThrows(
                ApiProblem.class,
                () ->
                    internal.scope(
                        delegated, new DeviceApi.ScopeRequest("device.manage", List.of(id))))
            .status());
    assertEquals(
        404,
        assertThrows(
                ApiProblem.class,
                () ->
                    internal.scope(
                        delegated,
                        new DeviceApi.ScopeRequest(
                            "workorder.create",
                            List.of(
                                create("IPC-FOREIGN", DeviceType.IPC, "AREA-B", Map.of())
                                    .path("device")
                                    .path("deviceId")
                                    .asText()))))
            .status());
  }

  @Test
  void publicActorCannotUseInternalOperationsOrAgentCredentialForAssetWrites() throws Exception {
    http.perform(
            post("/internal/v1/devices/references")
                .contentType("application/json")
                .content("{\"deviceIds\":[\"ipc-1\"]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SERVICE_TOKEN_INVALID"));
    var delegated =
        Jwt.withTokenValue("delegated-fixture")
            .header("alg", "RS256")
            .subject("capyiot-agent-service")
            .claim("iss", "capyiot-agent-service")
            .claim("aud", List.of("capyiot-workflow"))
            .build();
    http.perform(
            post("/api/v1/devices")
                .with(jwt().jwt(delegated))
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/internal/device-bridge/references")
                .header("X-Core-Service-Key", "fixture-stable-fingerprint-key-over-32-characters")
                .contentType("application/json")
                .content("{\"deviceIds\":[\"ipc-1\"]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void historicalEventsRemainReadOnlyAndRespectCurrentScope() {
    String id = device("IPC-LEGACY", DeviceType.IPC);
    String hidden =
        create("IPC-B", DeviceType.IPC, "AREA-B", Map.of())
            .path("device")
            .path("deviceId")
            .asText();
    for (String target : List.of(id, hidden))
      store.db.update(
          "INSERT INTO"
              + " device_event(event_id,campus_id,device_id,event_type,session_epoch,seq,occurred_at,received_at,trace_id,processing_status,payload_json,envelope_hash,kafka_partition,kafka_offset)"
              + " VALUES(?,?,?,'DEVICE_FAULT',1,1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),?,'PROCESSED','{}',?,0,1)",
          key(),
          target.equals(id) ? "CAMPUS-A" : "PARK-B",
          target,
          key(),
          "a".repeat(64));
    store.db.update("UPDATE device SET legacy_only=TRUE,device_type=NULL WHERE id=?", id);
    assertEquals(1L, historical.list(supervisor, null, null, null, null, null, 1, 50).get("total"));
    assertEquals(
        0L,
        ((Number) historical.list(supervisor, hidden, null, null, null, null, 1, 50).get("total"))
            .longValue());
    assertEquals(
        0L,
        ((Number) assets.events(supervisor, null, null, null, null, null, 1, 50).get("total"))
            .longValue());
  }
}
