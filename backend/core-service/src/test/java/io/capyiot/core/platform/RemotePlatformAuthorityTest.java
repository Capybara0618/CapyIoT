package io.capyiot.core.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.core.ApiProblem;
import io.capyiot.service.ServiceFailure;
import io.capyiot.service.ServiceTransport;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;

class RemotePlatformAuthorityTest {
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void request(){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));}
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    Jwt actor(){return Jwt.withTokenValue("a.b.c").header("alg","RS256").subject("user-1").claim("sid","s").claim("authz_ver",1L).build();}
    RemotePlatformAuthority client(ServiceTransport transport){return new RemotePlatformAuthority(URI.create("https://platform-service:8087"),"test-bridge-key-with-at-least-32-characters",transport,json);}
    @Test void unavailableAuthorityFailsClosedWithoutLocalFallback() {
        var attempts=new AtomicInteger();var remote=client((req,budget)->{attempts.incrementAndGet();throw ServiceFailure.unavailable("DEPENDENCY_UNAVAILABLE",null,true);});
        assertThatThrownBy(()->remote.current(actor())).isInstanceOf(ServiceFailure.class);assertThat(attempts).hasValue(2);
    }
    @Test void repeatedIdentityCheckWithinOneRequestUsesTheSameSnapshot()throws Exception {
        var identity=new AuthorizationContext("user-1","u","User",1,"s",List.of());byte[] bytes=json.writeValueAsBytes(identity);var calls=new AtomicInteger();
        var remote=client((req,budget)->{calls.incrementAndGet();return new ServiceTransport.Result(200,bytes);});
        assertThat(remote.current(actor()).userId()).isEqualTo("user-1");remote.current(actor());assertThat(calls).hasValue(1);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));remote.current(actor());assertThat(calls).hasValue(2);
    }
    @Test void subjectMismatchAndRevokedSessionNeverProduceAnActor()throws Exception {
        byte[] bytes=json.writeValueAsBytes(new AuthorizationContext("different-user","u","User",1,"s",List.of()));
        assertThatThrownBy(()->client((req,budget)->new ServiceTransport.Result(200,bytes)).current(actor())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->client((req,budget)->new ServiceTransport.Result(401,new byte[0])).current(actor()))
                .isInstanceOfSatisfying(ApiProblem.class,error->assertThat(error.status()).isEqualTo(401));
    }
    @Test void plaintextOriginIsRejectedEvenForTheMigrationBridge() {
        assertThatThrownBy(()->new RemotePlatformAuthority(URI.create("http://platform-service"),"test-bridge-key-with-at-least-32-characters",(req,budget)->null,json))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
