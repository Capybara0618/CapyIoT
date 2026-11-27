package io.capyiot.workflow.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
@ConditionalOnProperty(
    name = "capyiot.workorders.clients.enabled",
    havingValue = "true",
    matchIfMissing = true)
class WorkClients {
  @Bean
  Clock workClock() {
    return Clock.systemUTC();
  }

  @Bean
  ServiceKeyRing serviceKeys(@Value("${capyiot.workorders.service-key-file}") String file) {
    return new ServiceKeyRing(Path.of(file));
  }

  @Bean
  JdkServiceTransport transport(@Value("${capyiot.workorders.tls-ca-file}") String file) {
    return new JdkServiceTransport(ServiceTls.trust(Path.of(file)));
  }

  @Bean
  InternalHttpClient internalClient(
      ServiceKeyRing keys,
      Clock clock,
      JdkServiceTransport transport,
      ObjectMapper json,
      @Value("${capyiot.workorders.platform-url}") URI platform,
      @Value("${capyiot.workorders.device-url}") URI device,
      @Value("${capyiot.workorders.fault-url}") URI fault) {
    return new InternalHttpClient(
        Map.of(ServiceId.PLATFORM, platform, ServiceId.DEVICE, device, ServiceId.FAULT, fault),
        new ServiceTokenSigner(ServiceId.WORKFLOW, keys, clock),
        transport,
        json);
  }

  @Bean
  WorkPersonnel personnel(InternalHttpClient client) {
    return new WorkPersonnel(client);
  }

  @Bean
  WorkIdentity identities(InternalHttpClient client, WorkPersonnel personnel) {
    var identity = new RemoteWorkIdentity(client);
    identity.personnel(personnel);
    return identity;
  }

  @Bean
  WorkDevices devices(InternalHttpClient client) {
    return new WorkDevices(client);
  }

  @Bean
  WorkFaults faults(InternalHttpClient client) {
    return new RemoteWorkFaults(client);
  }

  @Bean
  JwtDecoder userTokens(
      JdkServiceTransport transport,
      Clock clock,
      @Value("${capyiot.workorders.user-jwks-uri}") URI user,
      @Value("${capyiot.workorders.agent-jwks-uri}") URI agent) {
    return new WorkJwtDecoder(
        new RemoteServiceKeys(
            Map.of(ServiceId.GATEWAY, user, ServiceId.AGENT, agent), transport, clock),
        clock);
  }

  @Bean
  ServiceTokenVerifier serviceTokens(
      JdkServiceTransport transport,
      Clock clock,
      @Value("${capyiot.workorders.fault-jwks-uri}") URI fault,
      @Value("${capyiot.workorders.device-jwks-uri}") URI device) {
    return new ServiceTokenVerifier(
        ServiceId.WORKFLOW,
        new RemoteServiceKeys(
            Map.of(ServiceId.FAULT, fault, ServiceId.DEVICE, device), transport, clock),
        clock);
  }
}
