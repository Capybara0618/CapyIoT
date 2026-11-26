package io.capyiot.platform;

import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.ServiceTokenVerifier;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@TestConfiguration
class PlatformTestConfiguration {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean JwtDecoder actors(Clock clock){return new PlatformJwtDecoder((caller,kid,budget)->PlatformIntegrationTest.RSA.toPublicJWK(),clock);}
    @Bean ServiceTokenVerifier services(Clock clock){return new ServiceTokenVerifier(ServiceId.PLATFORM,(caller,kid,budget)->PlatformIntegrationTest.RSA.toPublicJWK(),clock);}
    @Bean AssetScopeClient assets(){return new AssetScopeClient(){
        public DeviceApi.ResourceValidity validate(String park,List<String> areas){return new DeviceApi.ResourceValidity(park,park.equals("CAMPUS-A"),areas.stream().allMatch(a->a.startsWith("AREA-1")),0,java.time.Instant.now());}
        public boolean auditVisible(AuthorizationContext actor,String park,List<String> devices,List<String> areas){return false;}
    };}
}
