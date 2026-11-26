package io.capyiot.ingestion;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.*;
import java.util.UUID;

final class DeviceAuthorityClient {
    private final InternalHttpClient client;
    DeviceAuthorityClient(InternalHttpClient client){this.client=client;}
    DeviceApi.SessionContext session(String device,String session){
        var result=client.call(InternalEndpoint.DEVICE_SESSION_CONTEXT,null,null,DeviceApi.SessionContext.class,
                CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC()),UUID.randomUUID().toString(),device,session);
        if(!result.deviceId().equals(device)||!result.sessionId().equals(session))throw ServiceFailure.unavailable("SESSION_CONTEXT_INVALID",ServiceId.DEVICE,false);
        if(!result.expiresAt().isAfter(Instant.now()))throw ServiceFailure.denied(401,"DEVICE_AUTHENTICATION_FAILED");return result;
    }
    DeviceApi.IngestionCredential credential(String clientId){return client.call(InternalEndpoint.INGESTION_TOKEN,null,new DeviceApi.IngestionIdentity(clientId),DeviceApi.IngestionCredential.class,
            CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC()),UUID.randomUUID().toString());}
}
