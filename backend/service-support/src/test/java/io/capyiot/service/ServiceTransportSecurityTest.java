package io.capyiot.service;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTransportSecurityTest {
  @Test
  void actualHttpsRequiresBothTheTrustedCertificateAndMatchingHostname(@TempDir Path dir)
      throws Exception {
    var storeFile = dir.resolve("server.p12");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                storeFile.toString(),
                "-storepass",
                "fixture-password",
                "-keypass",
                "fixture-password",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("keytool.log").toFile())
            .start();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
    var store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(storeFile)) {
      store.load(input, "fixture-password".toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(store, "fixture-password".toCharArray());
    var serverTls = SSLContext.getInstance("TLS");
    serverTls.init(managers.getKeyManagers(), null, null);
    var certificate = dir.resolve("trusted.pem");
    Files.writeString(
        certificate,
        "-----BEGIN CERTIFICATE-----\n"
            + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(store.getCertificate("server").getEncoded())
            + "\n-----END CERTIFICATE-----\n");
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
    server.createContext(
        "/health",
        exchange -> {
          byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try (var trusted = new JdkServiceTransport(ServiceTls.trust(certificate));
        var untrusted = new JdkServiceTransport(SSLContext.getDefault())) {
      var good =
          HttpRequest.newBuilder(
                  URI.create("https://localhost:" + server.getAddress().getPort() + "/health"))
              .GET()
              .build();
      assertThat(
              trusted
                  .exchange(good, CallBudget.start(Duration.ofSeconds(10), Clock.systemUTC()))
                  .status())
          .isEqualTo(200);
      assertThatThrownBy(
              () ->
                  untrusted.exchange(
                      good, CallBudget.start(Duration.ofSeconds(10), Clock.systemUTC())))
          .isInstanceOfSatisfying(
              ServiceFailure.class,
              error -> assertThat(error.code()).isEqualTo("DEPENDENCY_TLS_INVALID"));
      var wrongHost =
          HttpRequest.newBuilder(
                  URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/health"))
              .GET()
              .build();
      assertThatThrownBy(
              () ->
                  trusted.exchange(
                      wrongHost, CallBudget.start(Duration.ofSeconds(10), Clock.systemUTC())))
          .isInstanceOfSatisfying(
              ServiceFailure.class,
              error -> assertThat(error.code()).isEqualTo("DEPENDENCY_TLS_INVALID"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rotatingPublicKeysRefreshesUnknownKidAndExpiredCacheNeverBypassesAnOutage()
      throws Exception {
    var oldKey = new RSAKeyGenerator(2048).keyID("old-service-key").generate();
    var newKey = new RSAKeyGenerator(2048).keyID("new-service-key").generate();
    var instant = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
    Clock clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant.get(), zone);
          }

          public Instant instant() {
            return instant.get();
          }
        };
    var published = new AtomicReference<>(new JWKSet(oldKey.toPublicJWK()).toString());
    var online = new AtomicBoolean(true);
    var requests = new AtomicInteger();
    var keys =
        new RemoteServiceKeys(
            Map.of(
                ServiceId.WORKFLOW,
                URI.create("https://workflow-service/.well-known/service-jwks.json")),
            (request, budget) -> {
              requests.incrementAndGet();
              if (!online.get())
                throw ServiceFailure.unavailable(
                    "SERVICE_JWKS_UNAVAILABLE", ServiceId.WORKFLOW, false);
              return new ServiceTransport.Result(
                  200, published.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            },
            clock);
    var endpoint = InternalEndpoint.RESERVATION_COMMIT;
    var path = endpoint.path("reservation");
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var verifier = new ServiceTokenVerifier(ServiceId.FAULT, keys, clock);
    var oldSigner =
        new ServiceTokenSigner(ServiceId.WORKFLOW, () -> oldKey, clock, Duration.ofSeconds(60));
    var first = CallBudget.start(Duration.ofSeconds(10), clock);
    var oldToken = "Bearer " + oldSigner.sign(endpoint, path, null, body, first);
    verifier.verify(endpoint, "POST", path, oldToken, null, body, first.header(), first);
    published.set(new JWKSet(List.of(oldKey.toPublicJWK(), newKey.toPublicJWK())).toString());
    instant.set(instant.get().plusSeconds(6));
    var budget = CallBudget.start(Duration.ofSeconds(10), clock);
    var signer =
        new ServiceTokenSigner(ServiceId.WORKFLOW, () -> newKey, clock, Duration.ofSeconds(60));
    verifier.verify(
        endpoint,
        "POST",
        path,
        "Bearer " + signer.sign(endpoint, path, null, body, budget),
        null,
        body,
        budget.header(),
        budget);
    assertThat(keys.resolve(ServiceId.WORKFLOW, oldKey.getKeyID(), budget).getKeyID())
        .isEqualTo(oldKey.getKeyID());
    assertThat(requests).hasValue(2);
    published.set(new JWKSet(newKey.toPublicJWK()).toString());
    instant.set(instant.get().plusSeconds(61));
    assertThatThrownBy(
            () ->
                keys.resolve(
                    ServiceId.WORKFLOW,
                    oldKey.getKeyID(),
                    CallBudget.start(Duration.ofSeconds(10), clock)))
        .isInstanceOfSatisfying(
            ServiceFailure.class, error -> assertThat(error.status()).isEqualTo(401));
    online.set(false);
    instant.set(instant.get().plusSeconds(61));
    assertThatThrownBy(
            () ->
                keys.resolve(
                    ServiceId.WORKFLOW,
                    newKey.getKeyID(),
                    CallBudget.start(Duration.ofSeconds(10), clock)))
        .isInstanceOfSatisfying(
            ServiceFailure.class, error -> assertThat(error.status()).isEqualTo(503));
  }
}
