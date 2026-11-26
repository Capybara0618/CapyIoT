package io.capyiot.core.ports;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.platform.AuthorizationContext;
import io.capyiot.service.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** MS-03 bridge only. No device datasource, store, or automatic fallback; removed with Core in MS-08. */
@Component
@ConditionalOnProperty(name="capyiot.platform.test-fixture",havingValue="false",matchIfMissing=true)
public final class RemoteDeviceQuery implements DeviceQueryPort,EvidenceQueryPort {
    private final URI origin;private final String key;private final ServiceTransport transport;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public RemoteDeviceQuery(@Value("${capyiot.device.url:https://device-service:8082}") URI origin,@Value("${capyiot.foundation.service-key}") String key,
            @Value("${capyiot.device.tls-ca-file:/run/secrets/internal-ca.pem}") String ca,ObjectMapper json){this(origin,key,new JdkServiceTransport(ServiceTls.trust(Path.of(ca))),json);}
    RemoteDeviceQuery(URI origin,String key,ServiceTransport transport,ObjectMapper json){
        if(!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!Set.of("","/").contains(origin.getPath())||key.length()<32)throw new IllegalArgumentException("Fixed TLS device authority required");
        this.origin=origin;this.key=key;this.transport=transport;this.json=json.copy();
    }
    @jakarta.annotation.PreDestroy public void close()throws Exception{if(transport instanceof AutoCloseable c)c.close();}
    private CallBudget budget(){
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a){var r=a.getRequest();if(r.getAttribute("capyiot.call-budget") instanceof CallBudget b)return b;var b=CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC());r.setAttribute("capyiot.call-budget",b);return b;}
        return CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC());
    }
    private String actor(AuthorizationContext expected){var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof Jwt token))throw ApiProblem.forbidden();
        String actor="capyiot-agent-service".equals(token.getClaimAsString("iss"))?token.getClaimAsString("actorUserId"):token.getSubject();
        if(!expected.userId().equals(actor))throw ApiProblem.forbidden();return token.getTokenValue();}
    private <T> T read(String operation,Object body,String actor,Class<T> output,CallBudget budget){
        if(!Set.of("references","scope","visible","network","evidence").contains(operation))throw new IllegalArgumentException("Device bridge operation not allowed");
        byte[] bytes;try{bytes=json.writeValueAsBytes(body);}catch(Exception e){throw ApiProblem.invalid("设备请求不符合契约");}
        for(int attempt=0;attempt<2;attempt++)try{
            var builder=HttpRequest.newBuilder(origin.resolve("/internal/device-bridge/"+operation)).timeout(budget.remaining(Duration.ofSeconds(3)))
                    .header("X-Core-Service-Key",key).header("Content-Type","application/json").header(InternalEndpoint.DEADLINE_HEADER,budget.header());
            if(actor!=null)builder.header("Authorization","Bearer "+actor);
            var response=transport.exchange(builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),budget);
            if(response.status()==401)throw new ApiProblem(401,"DEVICE_AUTHORITY_UNAUTHORIZED","设备授权上下文无效");
            if(response.status()==403)throw ApiProblem.forbidden();if(response.status()==404)throw ApiProblem.missing();
            if(response.status()>=500)throw ServiceFailure.unavailable("DEVICE_AUTHORITY_UNAVAILABLE",ServiceId.DEVICE,true);
            if(response.status()!=200)throw ServiceFailure.unavailable("DEVICE_INVALID_RESPONSE",ServiceId.DEVICE,false);
            try{T result=json.readValue(response.body(),output);if(result==null)throw new IllegalArgumentException();budget.check();return result;}
            catch(ServiceFailure e){throw e;}catch(Exception e){throw ServiceFailure.unavailable("DEVICE_INVALID_RESPONSE",ServiceId.DEVICE,false);}
        }catch(ServiceFailure e){if(!e.retryable()||attempt==1)throw e;budget.check();}
        throw new IllegalStateException("Unreachable device query state");
    }
    private DeviceRef ref(DeviceApi.Resource r){return new DeviceRef(r.deviceId(),r.parkId(),r.areaId(),r.assetCode(),r.deviceType(),r.name(),r.assetStatus());}
    public DeviceRef device(String id){var result=read("references",new DeviceApi.Batch(List.of(id)),null,DeviceApi.ScopeResult.class,budget());
        if(result.devices().size()!=1||!result.devices().getFirst().deviceId().equals(id))throw ServiceFailure.unavailable("DEVICE_INVALID_RESPONSE",ServiceId.DEVICE,false);return ref(result.devices().getFirst());}
    public List<DeviceRef> requireVisibleDevices(AuthorizationContext actor,String permission,Collection<String> ids){
        var result=new ArrayList<DeviceRef>();var all=List.copyOf(ids);var budget=budget();String token=actor(actor);
        for(int start=0;start<all.size();start+=100){var batch=all.subList(start,Math.min(start+100,all.size()));var response=read("scope",new DeviceApi.ScopeRequest(permission,batch),token,DeviceApi.ScopeResult.class,budget);
            if(!new HashSet<>(batch).equals(response.devices().stream().map(DeviceApi.Resource::deviceId).collect(java.util.stream.Collectors.toSet()))||response.devices().size()!=batch.size())throw ServiceFailure.unavailable("DEVICE_SCOPE_INCOMPLETE",ServiceId.DEVICE,false);
            response.devices().forEach(r->result.add(ref(r)));}return List.copyOf(result);
    }
    public Set<String> visibleDeviceIds(AuthorizationContext actor,String permission){var result=new HashSet<String>();String cursor=null;var budget=budget();String token=actor(actor);int pages=0;
        do{var page=read("visible",new DeviceApi.VisiblePageRequest(permission,cursor),token,DeviceApi.VisiblePage.class,budget);
            if(page.nextCursor()!=null&&cursor!=null&&page.nextCursor().compareTo(cursor)<=0)throw ServiceFailure.unavailable("DEVICE_CURSOR_INVALID",ServiceId.DEVICE,false);
            result.addAll(page.deviceIds());cursor=page.nextCursor();if(++pages>100)throw ServiceFailure.unavailable("DEVICE_SCOPE_QUERY_LIMIT",ServiceId.DEVICE,false);
        }while(cursor!=null);return Set.copyOf(result);
    }
    public List<Map<String,Object>> networkRelations(String park,Instant asOf){var result=new ArrayList<Map<String,Object>>();String cursor=null;var budget=budget();int pages=0;
        do{var page=read("network",new DeviceApi.NetworkPageRequest(park,asOf,cursor),null,DeviceApi.NetworkPage.class,budget);
            if(!asOf.equals(page.asOf())||page.nextCursor()!=null&&cursor!=null&&page.nextCursor().compareTo(cursor)<=0)throw ServiceFailure.unavailable("TOPOLOGY_CURSOR_INVALID",ServiceId.DEVICE,false);
            for(var r:page.relations()){Map<String,Object> row=new LinkedHashMap<>();row.put("relationId",r.relationId());row.put("upstreamSwitchId",r.sourceDeviceId());row.put("downstreamDeviceId",r.targetDeviceId());row.put("upstreamPortRef",r.port());row.put("validFrom",r.validFrom());row.put("validTo",r.validTo());row.put("version",r.version());result.add(row);}
            cursor=page.nextCursor();if(++pages>100)throw ServiceFailure.unavailable("TOPOLOGY_QUERY_LIMIT",ServiceId.DEVICE,false);
        }while(cursor!=null);return List.copyOf(result);
    }
    public boolean visibleEvidence(AuthorizationContext actor,String id,Collection<String> targets){return read("evidence",new FaultApi.EvidenceRequest(id,List.copyOf(targets)),actor(actor),FaultApi.EvidenceResult.class,budget()).visible();}
}
