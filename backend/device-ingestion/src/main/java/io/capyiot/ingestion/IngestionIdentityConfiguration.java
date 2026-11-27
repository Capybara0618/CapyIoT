package io.capyiot.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IngestionIdentityConfiguration {
  @Bean
  ServiceKeyRing serviceKeys(
      @Value("${INGESTION_SERVICE_KEY_FILE:/run/secrets/ingestion-service-keys.json}")
          String file) {
    return new ServiceKeyRing(Path.of(file));
  }

  @Bean
  JdkServiceTransport serviceTransport(
      @Value("${SERVICE_TLS_CA_FILE:/run/secrets/internal-ca.pem}") String file) {
    return new JdkServiceTransport(ServiceTls.trust(Path.of(file)));
  }

  @Bean
  DeviceAuthorityClient deviceAuthority(
      @Value("${DEVICE_SERVICE_URL:https://device-service:8082}") URI url,
      ServiceKeyRing keys,
      JdkServiceTransport transport,
      ObjectMapper json) {
    return new DeviceAuthorityClient(
        new InternalHttpClient(
            Map.of(ServiceId.DEVICE, url),
            new ServiceTokenSigner(ServiceId.INGESTION, keys, Clock.systemUTC()),
            transport,
            json));
  }
}
