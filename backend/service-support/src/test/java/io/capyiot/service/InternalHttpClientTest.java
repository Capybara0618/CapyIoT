package io.capyiot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.capyiot.contract.internal.FaultApi;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InternalHttpClientTest {
    static RSAKey key;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-26T06:00:00Z"),ZoneOffset.UTC);
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @BeforeAll static void signingKey() throws Exception {key=new RSAKeyGenerator(2048).keyID("service-k1").generate();}
    CallBudget budget(){return CallBudget.start(Duration.ofSeconds(10),clock);}
    InternalHttpClient client(ServiceId caller,ServiceTransport transport) {
        return new InternalHttpClient(Map.of(ServiceId.FAULT,URI.create("https://alarm-service:8443")),
                new ServiceTokenSigner(caller,()->key,clock,Duration.ofSeconds(60)),transport,mapper);
    }
    ServiceTransport.Result response(int status,String body){return new ServiceTransport.Result(status,body.getBytes(StandardCharsets.UTF_8));}
    String ok(){return "{\"code\":\"OK\",\"message\":\"成功\",\"traceId\":\"trace-1\",\"data\":{\"devices\":[],\"checkedAt\":\"2026-11-26T06:00:00Z\",\"complete\":true}}";}

    @Test void queryRetriesAtMostOnceAndPreservesDeadlineAndIdentityHeaders() {
        var attempts=new AtomicInteger();String[] deadline=new String[1];
        var client=client(ServiceId.DEVICE,(request,b)->{
            assertThat(request.uri().getHost()).isEqualTo("alarm-service");
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
            assertThat(request.headers().firstValue(InternalEndpoint.SERVICE_AUTHORIZATION)).isPresent();
            String current=request.headers().firstValue(InternalEndpoint.DEADLINE_HEADER).orElseThrow();
            if(deadline[0]==null)deadline[0]=current;else assertThat(current).isEqualTo(deadline[0]);
            return attempts.incrementAndGet()==1?response(503,"{\"code\":\"UNAVAILABLE\"}"):response(200,ok());
        });
        var result=client.call(InternalEndpoint.HEALTH_BATCH,null,Map.of("deviceIds",java.util.List.of("IPC-1")),
                FaultApi.HealthResult.class,budget(),"correlation-1");
        assertThat(result.complete()).isTrue();assertThat(attempts).hasValue(2);
    }

    @Test void commandTimeoutDoesNotRetryAndMarksOutcomeUnknown() {
        var attempts=new AtomicInteger();
        var client=client(ServiceId.WORKFLOW,(request,b)->{
            attempts.incrementAndGet();throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT",ServiceId.FAULT,true);
        });
        assertThatThrownBy(()->client.call(InternalEndpoint.RESERVATION_COMMIT,null,Map.of(),FaultApi.Reservation.class,
                budget(),"correlation-1","reservation-1"))
                .isInstanceOfSatisfying(ServiceFailure.class,error->{
                    assertThat(error.outcome()).isEqualTo(ServiceFailure.Outcome.UNKNOWN);
                    assertThat(error.retryable()).isFalse();
                });
        assertThat(attempts).hasValue(1);
    }

    @Test void notFoundIsARejectionNotAnEmptySuccessfulSnapshot() {
        var client=client(ServiceId.WORKFLOW,(request,b)->response(404,"{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"sensitive-upstream-text\"}"));
        assertThatThrownBy(()->client.call(InternalEndpoint.RESERVATION_QUERY,null,null,FaultApi.Reservation.class,
                budget(),"correlation-1","operation-1"))
                .isInstanceOfSatisfying(ServiceFailure.class,error->{
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.outcome()).isEqualTo(ServiceFailure.Outcome.REJECTED);
                    assertThat(error.getMessage()).doesNotContain("sensitive-upstream-text");
                });
    }

    @Test void malformedSuccessAndRedirectDoNotBecomeNormalHealth() {
        for(var response:java.util.List.of(response(200,"{\"code\":\"OK\",\"data\":null}"),response(302,"secret-response"),
                response(200,ok()+" {}"))) {
            var client=client(ServiceId.DEVICE,(request,b)->response);
            assertThatThrownBy(()->client.call(InternalEndpoint.HEALTH_BATCH,null,Map.of(),FaultApi.HealthResult.class,
                    budget(),"correlation-1"))
                    .isInstanceOfSatisfying(ServiceFailure.class,error->{
                        assertThat(error.status()).isEqualTo(503);assertThat(error.retryable()).isFalse();
                        assertThat(error.getMessage()).doesNotContain("secret-response");
                    });
        }
    }

    @Test void plaintextAndUserControlledOriginsAreRejectedBeforeTransport() {
        for(String uri:java.util.List.of("http://alarm-service","https://user:password@alarm-service", "https://alarm-service/path", "https://alarm-service?url=other"))
            assertThatThrownBy(()->new InternalHttpClient(Map.of(ServiceId.FAULT,URI.create(uri)),
                    new ServiceTokenSigner(ServiceId.DEVICE,()->key,clock,Duration.ofSeconds(60)),(request,b)->response(200,ok()),mapper))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void authorizationFailureNeverRetriesAndExpiredBudgetDoesNotSend() {
        var attempts=new AtomicInteger();var client=client(ServiceId.DEVICE,(request,b)->{attempts.incrementAndGet();return response(403,"{}");});
        assertThatThrownBy(()->client.call(InternalEndpoint.HEALTH_BATCH,null,Map.of(),FaultApi.HealthResult.class,budget(),"correlation-1"))
                .isInstanceOfSatisfying(ServiceFailure.class,error->assertThat(error.status()).isEqualTo(403));
        assertThat(attempts).hasValue(1);
        assertThatThrownBy(()->CallBudget.receive("1",Duration.ofSeconds(10),clock)).isInstanceOf(ServiceFailure.class);
        assertThat(attempts).hasValue(1);
    }
}
