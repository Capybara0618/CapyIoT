package io.capyiot.platform;

import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.service.InternalHttpClient;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class HttpAssetScopeClient implements AssetScopeClient {
    private final InternalHttpClient client;
    HttpAssetScopeClient(InternalHttpClient client){this.client=client;}
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Remote resource lookup cannot run in a platform transaction");}
    public DeviceApi.ResourceValidity validate(String park,List<String> areas) {
        outsideTransaction();return client.call(InternalEndpoint.RESOURCE_VALIDATION,null,new DeviceApi.ResourceValidation(park,areas),
                DeviceApi.ResourceValidity.class,RequestCalls.budget(),RequestCalls.correlation());
    }
    public boolean auditVisible(AuthorizationContext actor,String park,List<String> devices,List<String> areas) {
        if(devices.isEmpty()&&areas.isEmpty())return false;outsideTransaction();
        var result=client.call(InternalEndpoint.AUDIT_SCOPE,RequestCalls.actor(),new DeviceApi.AuditScopeRequest(park,devices,areas),
                DeviceApi.AuditScopeResult.class,RequestCalls.budget(),RequestCalls.correlation());return result.visible();
    }
}
