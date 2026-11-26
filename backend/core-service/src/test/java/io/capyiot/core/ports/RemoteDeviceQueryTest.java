package io.capyiot.core.ports;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.*;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.platform.AuthorizationContext;
import io.capyiot.service.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;

class RemoteDeviceQueryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void request(){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));}
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();SecurityContextHolder.clearContext();}
    RemoteDeviceQuery client(ServiceTransport transport){return new RemoteDeviceQuery(URI.create("https://device-service:8082"),"fixture-bridge-key-with-at-least-32-characters",transport,json);}
    AuthorizationContext actor(){return new AuthorizationContext("user-1","u","User",1,"s",List.of());}
    void authenticate(String subject){SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("a.b.c").header("alg","RS256").subject(subject).build()));}
    byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(Exception e){throw new IllegalStateException(e);}}
    DeviceApi.Resource device(String id){return new DeviceApi.Resource(id,"park-1","area-1",id,"IPC","IPC","IN_SERVICE",List.of(),1);}
    @Test void outageRetriesOnlyQueriesAndDoesNotInventLocalState(){
        var attempts=new AtomicInteger();var remote=client((req,budget)->{attempts.incrementAndGet();throw ServiceFailure.unavailable("DEVICE_AUTHORITY_UNAVAILABLE",ServiceId.DEVICE,true);});
        assertThatThrownBy(()->remote.device("ipc-1")).isInstanceOf(ServiceFailure.class);assertThat(attempts).hasValue(2);
    }
    @Test void mismatchedActorNeverReachesTheDeviceAuthority(){
        authenticate("other-user");var calls=new AtomicInteger();var remote=client((req,budget)->{calls.incrementAndGet();return null;});
        assertThatThrownBy(()->remote.requireVisibleDevices(actor(),"device.read",List.of("ipc-1"))).isInstanceOf(ApiProblem.class);assertThat(calls).hasValue(0);
    }
    @Test void partialScopeAndWrongDeviceReferenceFailClosed(){
        authenticate("user-1");var remote=client((req,budget)->new ServiceTransport.Result(200,bytes(new DeviceApi.ScopeResult(List.of(device("ipc-1")),Instant.now()))));
        assertThatThrownBy(()->remote.requireVisibleDevices(actor(),"device.read",List.of("ipc-1","ipc-2"))).isInstanceOf(ServiceFailure.class);
        assertThatThrownBy(()->remote.device("ipc-2")).isInstanceOf(ServiceFailure.class);
    }
    @Test void nonadvancingScopeCursorAndChangedHistoricalTimeAreRejected(){
        authenticate("user-1");var remote=client((req,budget)->new ServiceTransport.Result(200,bytes(new DeviceApi.VisiblePage(List.of("ipc-1"),"ipc-1",Instant.now()))));
        assertThatThrownBy(()->remote.visibleDeviceIds(actor(),"device.read")).isInstanceOf(ServiceFailure.class);
        var historical=client((req,budget)->new ServiceTransport.Result(200,bytes(new DeviceApi.NetworkPage(List.of(),null,Instant.parse("2026-01-02T00:00:00Z")))));
        assertThatThrownBy(()->historical.networkRelations("park-1",Instant.parse("2026-01-01T00:00:00Z"))).isInstanceOf(ServiceFailure.class);
    }
    @Test void bridgeRequiresTlsAndUsesFixedPaths(){
        assertThatThrownBy(()->new RemoteDeviceQuery(URI.create("http://device-service"),"fixture-bridge-key-with-at-least-32-characters",(r,b)->null,json)).isInstanceOf(IllegalArgumentException.class);
        var remote=client((req,budget)->{assertThat(req.uri().getPath()).isEqualTo("/internal/device-bridge/references");assertThat(req.headers().firstValue("Authorization")).isEmpty();return new ServiceTransport.Result(404,new byte[0]);});
        assertThatThrownBy(()->remote.device("ipc-1")).isInstanceOfSatisfying(ApiProblem.class,p->assertThat(p.status()).isEqualTo(404));
    }
}
