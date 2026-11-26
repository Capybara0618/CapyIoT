package io.capyiot.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="capyiot.platform.clients.enabled",havingValue="true",matchIfMissing=true)
class PlatformClients {
    @Bean Clock platformClock(){return Clock.systemUTC();}
    @Bean ServiceKeyRing serviceKeyRing(@Value("${capyiot.platform.key-file}") String file){return new ServiceKeyRing(Path.of(file));}
    @Bean JdkServiceTransport serviceTransport(@Value("${capyiot.platform.tls-ca-file}") String ca){return new JdkServiceTransport(ServiceTls.trust(Path.of(ca)));}
    @Bean InternalHttpClient deviceClient(@Value("${capyiot.platform.device-url}") URI url,ServiceKeyRing keys,Clock clock,JdkServiceTransport transport,ObjectMapper json) {
        return new InternalHttpClient(Map.of(ServiceId.DEVICE,url),new ServiceTokenSigner(ServiceId.PLATFORM,keys,clock),transport,json);
    }
    @Bean AssetScopeClient resources(InternalHttpClient client){return new HttpAssetScopeClient(client);}
    @Bean ServiceTokenVerifier serviceTokens(JdkServiceTransport transport,Clock clock,
            @Value("${capyiot.platform.gateway-service-jwks-uri}") URI gateway,
            @Value("${capyiot.platform.device-service-jwks-uri}") URI device,
            @Value("${capyiot.platform.fault-service-jwks-uri}") URI fault,
            @Value("${capyiot.platform.workflow-service-jwks-uri}") URI workflow,
            @Value("${capyiot.platform.agent-service-jwks-uri}") URI agent) {
        return new ServiceTokenVerifier(ServiceId.PLATFORM,new RemoteServiceKeys(Map.of(ServiceId.GATEWAY,gateway,ServiceId.DEVICE,device,
                ServiceId.FAULT,fault,ServiceId.WORKFLOW,workflow,ServiceId.AGENT,agent),transport,clock),clock);
    }
    @Bean org.springframework.security.oauth2.jwt.JwtDecoder actorTokens(JdkServiceTransport transport,Clock clock,
            @Value("${capyiot.platform.user-jwks-uri}") URI gateway,@Value("${capyiot.platform.agent-jwks-uri}") URI agent) {
        return new PlatformJwtDecoder(new RemoteServiceKeys(Map.of(ServiceId.GATEWAY,gateway,ServiceId.AGENT,agent),transport,clock),clock);
    }
}
