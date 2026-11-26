package io.capyiot.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import io.capyiot.contract.LocalTls;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** A short-lived test JWT proves real EMQX expiry; production token lifetime is tested separately. */
class SecureBrokerContractTest {
    @TempDir Path directory;
    @Test void realBrokerChecksRsaBindingAndDisconnectsWhenTheConnectTokenExpires()throws Exception{
        var signing=new RSAKeyGenerator(3072).keyID("ephemeral-broker-test").generate();
        var jwksReads=new java.util.concurrent.atomic.AtomicInteger();
        var jwks=HttpsServer.create(new InetSocketAddress("0.0.0.0",0),0);
        jwks.createContext("/.well-known/device-jwks.json",exchange->{
            byte[] body=new JWKSet(signing.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();jwksReads.incrementAndGet();
        });
        try{
            String keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
            Path storeFile=directory.resolve("broker.p12");
            var generated=new ProcessBuilder(keytool,"-genkeypair","-alias","broker","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost",
                "-ext","SAN=dns:localhost,ip:127.0.0.1,dns:emqx,dns:host.testcontainers.internal","-storetype","PKCS12","-keystore",storeFile.toString(),"-storepass","test-only-password","-keypass","test-only-password","-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
            assertThat(generated.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(generated.exitValue()).isZero();
            var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(storeFile)){store.load(input,"test-only-password".toCharArray());}
            var managers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());managers.init(store,"test-only-password".toCharArray());
            var tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,new java.security.SecureRandom());
            jwks.setHttpsConfigurator(new HttpsConfigurator(tls));jwks.start();Testcontainers.exposeHostPorts(jwks.getAddress().getPort());
            byte[] certificate=pem("CERTIFICATE",store.getCertificate("broker").getEncoded());
            byte[] privateKey=pem("PRIVATE KEY",store.getKey("broker","test-only-password".toCharArray()).getEncoded());
            Path ca=directory.resolve("ca.pem");Files.write(ca,certificate);
            String configuration=Files.readString(Path.of("../../infra/emqx/emqx.conf"))
                .replace("https://device-service:8082/.well-known/device-jwks.json","https://host.testcontainers.internal:"+jwks.getAddress().getPort()+"/.well-known/device-jwks.json")
                .replace("server_name_indication = \"device-service\"","server_name_indication = \"host.testcontainers.internal\"")
                .replace("api_key.bootstrap_file = \"/opt/emqx/etc/local-api-keys\"","");
            try(var broker=new GenericContainer<>("emqx/emqx:5.8.9").withExposedPorts(8883)
                .withEnv("EMQX_NODE__NAME","emqx@127.0.0.1").withEnv("EMQX_NODE__COOKIE",UUID.randomUUID().toString()).withEnv("ERL_FLAGS","+S 2:2")
                .withCopyToContainer(Transferable.of(configuration),"/opt/emqx/etc/emqx.conf")
                .withCopyToContainer(Transferable.of(certificate),"/opt/emqx/etc/local-tls/ca.pem")
                .withCopyToContainer(Transferable.of(certificate),"/opt/emqx/etc/internal-tls/ca.pem")
                .withCopyToContainer(Transferable.of(certificate),"/opt/emqx/etc/local-tls/server.pem")
                .withCopyToContainer(Transferable.of(privateKey,0644),"/opt/emqx/etc/local-tls/server.key")
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(90))){
                broker.start();
                try{
                await().atMost(Duration.ofSeconds(45)).until(()->jwksReads.get()>0);
                String url="ssl://"+broker.getHost()+":"+broker.getMappedPort(8883);
                Instant expires=Instant.now().plusSeconds(20);String token=token(signing,expires);
                // Paho runs its blocking connect, sender, receiver and callback loops on this executor.
                var workers=Executors.newScheduledThreadPool(6,Thread.ofPlatform().daemon().factory());
                try(var mqtt=new MqttClient(url,"ipc-contract",new MemoryPersistence(),workers)){
                    mqtt.setTimeToWait(10000);
                    var settings=new MqttConnectOptions();settings.setSocketFactory(LocalTls.sockets(ca.toString()));settings.setHttpsHostnameVerificationEnabled(true);
                    settings.setConnectionTimeout(3);settings.setKeepAliveInterval(5);settings.setUserName("wrong-device");settings.setPassword(token.toCharArray());
                    assertThatThrownBy(()->mqtt.connect(settings)).isInstanceOf(MqttException.class);
                    settings.setUserName("ipc-contract");var lastReason=new java.util.concurrent.atomic.AtomicInteger();
                    await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).until(()->{
                        try{mqtt.connect(settings);return mqtt.isConnected();}catch(MqttException notReady){lastReason.set(notReady.getReasonCode());return false;}
                    });
                    // No 15-minute sleep and no production TTL override: only this isolated signer uses 20s.
                    await().atMost(Duration.ofSeconds(35)).until(()->!mqtt.isConnected());
                    assertThat(Instant.now()).isAfterOrEqualTo(expires.minusSeconds(1));
                    assertThatThrownBy(()->mqtt.connect(settings)).isInstanceOf(MqttException.class);
                }finally{workers.shutdownNow();}
                }catch(Exception|AssertionError failure){
                    String logs=broker.getLogs().replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[REDACTED_JWT]");
                    Path report=Path.of("../../var/verification/broker-contract-diagnostic.log");Files.createDirectories(report.getParent());Files.writeString(report,"JWKS reads="+jwksReads.get()+"\n"+logs);
                    throw failure;
                }
            }
        }finally{jwks.stop(0);}
    }
    private static String token(RSAKey signing,Instant expiry)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer("capyiot-device-service").subject("ipc-contract").audience("capyiot-emqx")
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expiry)).jwtID(UUID.randomUUID().toString())
            .claim("username","ipc-contract").claim("clientid","ipc-contract").claim("sid","boot-contract").claim("sessionEpoch",1).claim("credentialVersion",1)
            .claim("acl",List.of(Map.of("permission","allow","action","publish","topic","capyiot/v1/devices/ipc-contract/sessions/boot-contract/events","qos",List.of(1),"retain",false),Map.of("permission","deny","action","all","topic","#"))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signing.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(signing));return jwt.serialize();
    }
    private static byte[] pem(String type,byte[] bytes){return ("-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n").getBytes(StandardCharsets.US_ASCII);}
}
