package io.capyiot.core.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.PlatformApi;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.ports.DeviceQueryPort;
import io.capyiot.service.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** MS-02 transitional adapter. Fixed, expiring legacy endpoints only; delete with Core in MS-08. */
@Component
@ConditionalOnProperty(name="capyiot.platform.test-fixture",havingValue="false",matchIfMissing=true)
public final class RemotePlatformAuthority implements PlatformAuthority {
    private final URI origin;private final String key;private final ServiceTransport transport;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public RemotePlatformAuthority(@Value("${capyiot.platform.url:https://platform-service:8087}") URI origin,
            @Value("${capyiot.foundation.service-key}") String key,@Value("${capyiot.platform.tls-ca-file:/run/secrets/internal-ca.pem}") String ca,ObjectMapper json) {
        this(origin,key,new JdkServiceTransport(ServiceTls.trust(Path.of(ca))),json);
    }
    RemotePlatformAuthority(URI origin,String key,ServiceTransport transport,ObjectMapper json) {
        if(!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null
                ||origin.getRawFragment()!=null||!Set.of("","/").contains(origin.getPath())||key.length()<32)
            throw new IllegalArgumentException("Fixed TLS platform origin and bridge identity are required");
        this.origin=origin;this.key=key;this.transport=transport;this.json=json.copy();
    }
    @PreDestroy public void close()throws Exception{if(transport instanceof AutoCloseable closeable)closeable.close();}
    private jakarta.servlet.http.HttpServletRequest request(){
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a))throw new IllegalStateException("Platform call requires request context");return a.getRequest();
    }
    private CallBudget budget(){var req=request();if(req.getAttribute("capyiot.call-budget") instanceof CallBudget b)return b;
        var b=CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC());req.setAttribute("capyiot.call-budget",b);return b;}
    private <T> T query(String path,String token,Object body,Class<T> type) {
        if(!Set.of("/internal/foundation/context","/internal/foundation/eligibility").contains(path))throw new IllegalArgumentException("Bridge route not allowed");
        var budget=budget();byte[] bytes;
        try{bytes=body==null?new byte[0]:json.writeValueAsBytes(body);}catch(Exception e){throw ApiProblem.invalid("平台请求不符合契约");}
        if(bytes.length>JdkServiceTransport.MAX_RESPONSE_BYTES)throw ApiProblem.invalid("平台请求过大");
        for(int attempt=0;attempt<2;attempt++)try{
            var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(budget.remaining(Duration.ofSeconds(3)))
                    .header("X-Core-Service-Key",key).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .header("X-CapyIoT-Deadline",budget.header()).header("X-Correlation-Id",request().getRequestId());
            var response=transport.exchange(body==null?builder.GET().build():builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),budget);
            if(response.status()==401)throw new ApiProblem(401,"SESSION_INVALID","当前会话失效");
            if(response.status()==403)throw ApiProblem.forbidden();
            if(response.status()>=500)throw ServiceFailure.unavailable("AUTHORIZATION_UNAVAILABLE",ServiceId.PLATFORM,true);
            if(response.status()!=200||response.body().length>JdkServiceTransport.MAX_RESPONSE_BYTES)throw ServiceFailure.unavailable("PLATFORM_INVALID_RESPONSE",ServiceId.PLATFORM,false);
            try{T result=json.readValue(response.body(),type);if(result==null)throw new IllegalArgumentException();budget.check();return result;}
            catch(ServiceFailure e){throw e;}catch(Exception e){throw ServiceFailure.unavailable("PLATFORM_INVALID_RESPONSE",ServiceId.PLATFORM,false);}
        }catch(ServiceFailure error){if(!error.retryable()||attempt==1)throw error;budget.check();}
        throw new IllegalStateException("Unreachable bridge state");
    }
    public AuthorizationContext current(Jwt jwt){
        String digest=ActorCredential.bearer("Bearer "+jwt.getTokenValue()).digest();var req=request();
        if(req.getAttribute("capyiot.platform-context") instanceof Cached cached&&cached.digest.equals(digest))return cached.actor;
        var actor=query("/internal/foundation/context",jwt.getTokenValue(),null,AuthorizationContext.class);
        String expected="capyiot-agent-service".equals(jwt.getClaimAsString("iss"))?jwt.getClaimAsString("actorUserId"):jwt.getSubject();
        if(actor.userId()==null||actor.grants()==null||actor.sessionId()==null||actor.authorizationVersion()<1)
            throw ServiceFailure.unavailable("PLATFORM_INVALID_RESPONSE",ServiceId.PLATFORM,false);
        if(!actor.userId().equals(expected))throw new ApiProblem(401,"SESSION_INVALID","平台返回身份不一致");
        req.setAttribute("capyiot.platform-context",new Cached(digest,actor));return actor;
    }
    private record Cached(String digest,AuthorizationContext actor){}
    private List<DeviceApi.Resource> resources(Collection<DeviceQueryPort.DeviceRef> devices){return devices.stream().map(d->new DeviceApi.Resource(d.deviceId(),d.parkId(),d.areaId(),d.assetCode(),d.deviceType(),d.name(),d.assetStatus(),List.of(),0)).toList();}
    private String actorToken(){var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof Jwt jwt))throw ApiProblem.forbidden();return jwt.getTokenValue();}
    public void requireRoleForDevices(String user,String role,Collection<DeviceQueryPort.DeviceRef> devices){
        var result=query("/internal/foundation/eligibility",actorToken(),new PlatformApi.EligibilityRequest(List.of(user),PlatformApi.PersonnelRole.valueOf(role),resources(devices)),PlatformApi.EligibilityResult.class);
        if(result.candidates().size()!=1||!result.candidates().getFirst().userId().equals(user)||!result.candidates().getFirst().eligible())throw ApiProblem.invalid("责任人员缺少履职资格");
    }
    public List<Map<String,Object>> assignmentCandidates(AuthorizationContext actor,List<DeviceQueryPort.DeviceRef> devices){
        actor.require("workorder.assign");var results=new ArrayList<Map<String,Object>>();String token=actorToken();
        for(var role:PlatformApi.PersonnelRole.values()){
            String cursor=null;int pages=0;
            do{var response=query("/internal/foundation/eligibility",token,new PlatformApi.EligibilityRequest(List.of(),role,resources(devices),cursor),PlatformApi.EligibilityResult.class);
                response.candidates().stream().filter(PlatformApi.Eligibility::eligible).forEach(c->results.add(Map.of("userId",c.userId(),"displayName",c.displayName(),"roleCode",c.role().name())));
                cursor=response.nextCursor();if(++pages>20||results.size()>1000)throw ServiceFailure.unavailable("CANDIDATE_QUERY_LIMIT",ServiceId.PLATFORM,false);
            }while(cursor!=null);
        }return List.copyOf(results);
    }
}
