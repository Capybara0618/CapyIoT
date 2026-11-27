package io.capyiot.alarm.fault;

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
    name = "capyiot.faults.clients.enabled",
    havingValue = "true",
    matchIfMissing = true)
class FaultClients {
  @Bean
  Clock faultClock() {
    return Clock.systemUTC();
  }

  @Bean
  ServiceKeyRing serviceKeys(@Value("${capyiot.faults.service-key-file}") String file) {
    return new ServiceKeyRing(Path.of(file));
  }

  @Bean
  JdkServiceTransport transport(@Value("${capyiot.faults.tls-ca-file}") String file) {
    return new JdkServiceTransport(ServiceTls.trust(Path.of(file)));
  }

  @Bean
  InternalHttpClient internalClient(
      ServiceKeyRing keys,
      Clock clock,
      JdkServiceTransport transport,
      ObjectMapper json,
      io.micrometer.core.instrument.MeterRegistry meters,
      @Value("${capyiot.faults.platform-url}") URI platform,
      @Value("${capyiot.faults.device-url}") URI device,
      @Value("${capyiot.faults.workflow-url}") URI workflow) {
    return new InternalHttpClient(
        Map.of(
            ServiceId.PLATFORM, platform, ServiceId.DEVICE, device, ServiceId.WORKFLOW, workflow),
        new ServiceTokenSigner(ServiceId.FAULT, keys, clock),
        (request, budget) -> {
          try {
            var result = transport.exchange(request, budget);
            if (result.status() >= 500)
              meters
                  .counter(
                      "capyiot.fault.dependency.failures",
                      "dependency",
                      request.uri().getHost(),
                      "reason",
                      "HTTP_UNAVAILABLE")
                  .increment();
            return result;
          } catch (ServiceFailure failure) {
            meters
                .counter(
                    "capyiot.fault.dependency.failures",
                    "dependency",
                    request.uri().getHost(),
                    "reason",
                    failure.code())
                .increment();
            throw failure;
          }
        },
        json);
  }

  @Bean
  FaultIdentity identities(InternalHttpClient client) {
    return new RemoteFaultIdentity(client);
  }

  @Bean
  FaultDevices devices(InternalHttpClient client) {
    return new FaultDevices(client);
  }

  @Bean
  RemoteFaultOrders orders(InternalHttpClient client) {
    return new RemoteFaultOrders(client);
  }

  @Bean
  JwtDecoder userTokens(
      JdkServiceTransport transport,
      Clock clock,
      @Value("${capyiot.faults.user-jwks-uri}") URI user,
      @Value("${capyiot.faults.agent-jwks-uri}") URI agent) {
    return new FaultJwtDecoder(
        new RemoteServiceKeys(
            Map.of(ServiceId.GATEWAY, user, ServiceId.AGENT, agent), transport, clock),
        clock);
  }

  @Bean
  ServiceTokenVerifier serviceTokens(
      JdkServiceTransport transport,
      Clock clock,
      @Value("${capyiot.faults.workflow-jwks-uri}") URI workflow,
      @Value("${capyiot.faults.device-jwks-uri}") URI device) {
    return new ServiceTokenVerifier(
        ServiceId.FAULT,
        new RemoteServiceKeys(
            Map.of(ServiceId.WORKFLOW, workflow, ServiceId.DEVICE, device), transport, clock),
        clock);
  }
}
