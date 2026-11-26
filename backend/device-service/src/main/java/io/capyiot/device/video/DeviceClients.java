package io.capyiot.device.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
@ConditionalOnProperty(name="capyiot.device.clients.enabled",havingValue="true",matchIfMissing=true)
class DeviceClients {
    @Bean Clock deviceClock(){return Clock.systemUTC();}
    @Bean ServiceKeyRing serviceKeys(@Value("${capyiot.device.service-key-file}") String file){return new ServiceKeyRing(Path.of(file));}
    @Bean JdkServiceTransport transport(@Value("${capyiot.device.tls-ca-file}") String file){return new JdkServiceTransport(ServiceTls.trust(Path.of(file)));}
    @Bean InternalHttpClient internalClient(ServiceKeyRing keys,Clock clock,JdkServiceTransport transport,ObjectMapper json,
            @Value("${capyiot.device.platform-url}") URI platform,@Value("${capyiot.device.fault-url}") URI fault,@Value("${capyiot.device.workflow-url}") URI workflow){
        return new InternalHttpClient(Map.of(ServiceId.PLATFORM,platform,ServiceId.FAULT,fault,ServiceId.WORKFLOW,workflow),new ServiceTokenSigner(ServiceId.DEVICE,keys,clock),transport,json);
    }
    @Bean DeviceIdentity identities(InternalHttpClient client){return new RemoteDeviceIdentity(client);}
    @Bean HealthQueryPort health(InternalHttpClient client){return ids->client.call(InternalEndpoint.HEALTH_BATCH,null,new DeviceApi.Batch(ids.stream().sorted().toList()),FaultApi.HealthResult.class,RequestCalls.budget(),RequestCalls.correlation());}
    @Bean AssignmentQueryPort assignments(InternalHttpClient client,AssetStore store){return (user,park,relation)->{
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Assignment facts require an unlocked local transaction");
        var candidates=store.db.queryForList("SELECT id FROM device WHERE campus_id=? AND NOT legacy_only AND device_type IS NOT NULL ORDER BY id",String.class,park);
        var allowed=new HashSet<String>();
        for(int start=0;start<candidates.size();start+=100){var batch=candidates.subList(start,Math.min(start+100,candidates.size()));
            var result=client.call(InternalEndpoint.ASSIGNMENT_FACTS,null,new WorkOrderApi.AssignmentRequest(user,park,PlatformApi.AssignmentRelation.valueOf(relation),batch),WorkOrderApi.AssignmentResult.class,RequestCalls.budget(),RequestCalls.correlation());
            if(!new HashSet<>(batch).containsAll(result.assignedDeviceIds()))throw ServiceFailure.unavailable("ASSIGNMENT_INVALID_RESPONSE",ServiceId.WORKFLOW,false);
            allowed.addAll(result.assignedDeviceIds());
        }return Set.copyOf(allowed);
    };}
    @Bean JwtDecoder userTokens(JdkServiceTransport transport,Clock clock,@Value("${capyiot.device.user-jwks-uri}") URI jwks,@Value("${capyiot.device.agent-jwks-uri}") URI agent){
        return new DeviceJwtDecoder(new RemoteServiceKeys(Map.of(ServiceId.GATEWAY,jwks,ServiceId.AGENT,agent),transport,clock),clock);
    }
    @Bean ServiceTokenVerifier serviceTokens(JdkServiceTransport transport,Clock clock,@Value("${capyiot.device.platform-jwks-uri}") URI platform,
            @Value("${capyiot.device.fault-jwks-uri}") URI fault,@Value("${capyiot.device.workflow-jwks-uri}") URI workflow,@Value("${capyiot.device.ingestion-jwks-uri}") URI ingestion){
        return new ServiceTokenVerifier(ServiceId.DEVICE,new RemoteServiceKeys(Map.of(ServiceId.PLATFORM,platform,ServiceId.FAULT,fault,ServiceId.WORKFLOW,workflow,ServiceId.INGESTION,ingestion),transport,clock),clock);
    }
}
