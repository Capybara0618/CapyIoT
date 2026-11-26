package io.capyiot.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Fixed endpoints only. Queries may retry once; commands never retry automatically. */
public final class InternalHttpClient {
    private static final Set<String> SAFE_ERRORS=Set.of("VALIDATION_ERROR","RESOURCE_NOT_FOUND","PERMISSION_DENIED",
            "TOKEN_INVALID","SESSION_INVALID","AUTHORIZATION_CHANGED","IDEMPOTENCY_CONFLICT","VERSION_CONFLICT",
            "DUPLICATE_RESOURCE","SERVICE_SCOPE_DENIED","SERVICE_TOKEN_INVALID","ACTOR_TOKEN_INVALID","DEVICE_AUTHENTICATION_FAILED","DEVICE_AUTH_RATE_LIMITED");
    private final Map<ServiceId,URI> origins;
    private final ServiceTokenSigner signer;
    private final ServiceTransport transport;
    private final ObjectMapper json;
    public InternalHttpClient(Map<ServiceId,URI> origins,ServiceTokenSigner signer,ServiceTransport transport,ObjectMapper mapper) {
        var copy=new EnumMap<ServiceId,URI>(ServiceId.class);
        origins.forEach((service,uri)->copy.put(service,ServiceUris.https(uri,true)));
        this.origins=Map.copyOf(copy);this.signer=signer;this.transport=transport;
        this.json=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16384).maxNumberLength(32).build());
    }
    public <T> T call(InternalEndpoint operation,ActorCredential actor,Object input,Class<T> output,
                       CallBudget budget,String correlationId,String... ids) {
        if(correlationId==null||!correlationId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid correlation ID");
        if(!operation.allows(signer.caller()))throw ServiceFailure.denied(403,"SERVICE_SCOPE_DENIED");
        URI origin=origins.get(operation.target());
        if(origin==null)throw ServiceFailure.unavailable("DEPENDENCY_NOT_CONFIGURED",operation.target(),false);
        String path=operation.path(ids);byte[] bytes;
        try {
            if(operation.method().equals("GET")&&input!=null)throw new IllegalArgumentException("GET cannot have a body");
            bytes=input==null?new byte[0]:json.writeValueAsBytes(input);
            if(bytes.length>JdkServiceTransport.MAX_RESPONSE_BYTES)throw new IllegalArgumentException("Request is too large");
        }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalArgumentException("Invalid contract body");}
        for(int attempt=0;attempt<(operation.query()?2:1);attempt++) {
            boolean started=false;
            try {
                budget.check();String token=signer.sign(operation,path,actor,bytes,budget);
                var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(budget.remaining(Duration.ofSeconds(3)))
                        .header(InternalEndpoint.SERVICE_AUTHORIZATION,"Bearer "+token).header("Accept","application/json")
                        .header("Content-Type","application/json").header(InternalEndpoint.DEADLINE_HEADER,budget.header())
                        .header("X-Correlation-Id",correlationId).header("X-Request-Id",correlationId);
                if(actor!=null)builder.header("Authorization",actor.authorization());
                var request=builder.method(operation.method(),bytes.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                started=true;var result=transport.exchange(request,budget);budget.check();
                if(result.body().length>JdkServiceTransport.MAX_RESPONSE_BYTES)throw ServiceFailure.unavailable("DEPENDENCY_RESPONSE_TOO_LARGE",operation.target(),false);
                if(result.status()<200||result.status()>=300)throw rejection(result,operation.target());
                try {
                    var envelope=json.readTree(result.body());
                    if(envelope==null||!envelope.isObject()||!"OK".equals(envelope.path("code").asText())
                            ||!envelope.hasNonNull("data")||!envelope.path("traceId").isTextual()
                            ||envelope.path("traceId").asText().isBlank()||envelope.path("traceId").asText().length()>128)throw new IllegalArgumentException();
                    T decoded=json.treeToValue(envelope.get("data"),output);budget.check();return decoded;
                }catch(ServiceFailure error){throw error;}
                catch(Exception error){throw ServiceFailure.unavailable("DEPENDENCY_INVALID_RESPONSE",operation.target(),false);}
            }catch(ServiceFailure error) {
                error=error.at(operation.target());
                if(!operation.query()&&started)throw error.status()>=500?error.unknown():error;
                if(!operation.query()||!error.retryable()||attempt==1)throw error;
                budget.check();
            }
        }
        throw new IllegalStateException("Unreachable call state");
    }
    private ServiceFailure rejection(ServiceTransport.Result response,ServiceId target) {
        int status=response.status();
        if(status==502||status==503||status==504)return ServiceFailure.unavailable("DEPENDENCY_UNAVAILABLE",target,true);
        if(status>=500||status<400)return ServiceFailure.unavailable("DEPENDENCY_INVALID_RESPONSE",target,false);
        String code="DEPENDENCY_REJECTED";
        try {
            var envelope=json.readTree(response.body());String candidate=envelope.path("code").asText();
            if(SAFE_ERRORS.contains(candidate))code=candidate;
        }catch(Exception ignored){}
        return new ServiceFailure(status,code,target,false,ServiceFailure.Outcome.REJECTED);
    }
}
