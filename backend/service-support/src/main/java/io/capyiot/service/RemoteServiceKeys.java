package io.capyiot.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.capyiot.contract.internal.ServiceId;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Fixed issuer-to-JWKS map; a token's jku/x5u is never used as a URL. */
public final class RemoteServiceKeys implements ServiceTokenVerifier.Keys {
    private static final class Entry {
        final URI uri;
        final ReentrantLock lock=new ReentrantLock();
        Map<String,RSAKey> keys=Map.of();
        Instant expires=Instant.MIN;
        Instant lastAttempt=Instant.MIN;
        Entry(URI uri){this.uri=ServiceUris.https(uri,false);}
    }
    private final Map<ServiceId,Entry> entries;
    private final ServiceTransport transport;
    private final Clock clock;
    public RemoteServiceKeys(Map<ServiceId,URI> trusted,ServiceTransport transport,Clock clock) {
        var entries=new EnumMap<ServiceId,Entry>(ServiceId.class);
        trusted.forEach((caller,uri)->entries.put(caller,new Entry(uri)));
        this.entries=Map.copyOf(entries);this.transport=transport;this.clock=clock;
    }
    @Override public RSAKey resolve(ServiceId caller,String kid,CallBudget budget) {
        Entry entry=entries.get(caller);
        if(entry==null)throw ServiceFailure.denied(401,"SERVICE_TOKEN_INVALID");
        boolean locked=false;
        try {
            locked=entry.lock.tryLock(budget.remaining(Duration.ofSeconds(3)).toNanos(),TimeUnit.NANOSECONDS);
            if(!locked)throw ServiceFailure.unavailable("SERVICE_JWKS_UNAVAILABLE",caller,true);
            budget.check();Instant now=clock.instant();
            if(now.isBefore(entry.expires)&&entry.keys.containsKey(kid))return entry.keys.get(kid);
            if(now.isBefore(entry.lastAttempt.plusSeconds(5))) {
                if(now.isBefore(entry.expires))throw ServiceFailure.denied(401,"SERVICE_TOKEN_INVALID");
                throw ServiceFailure.unavailable("SERVICE_JWKS_UNAVAILABLE",caller,false);
            }
            entry.lastAttempt=now;
            var request=HttpRequest.newBuilder(entry.uri).timeout(budget.remaining(Duration.ofSeconds(3)))
                    .header("Accept","application/json").GET().build();
            var response=transport.exchange(request,budget);
            if(response.status()!=200||response.body().length>65536)throw ServiceFailure.unavailable("SERVICE_JWKS_UNAVAILABLE",caller,false);
            var parsed=JWKSet.parse(new String(response.body(),StandardCharsets.UTF_8));var keys=new HashMap<String,RSAKey>();
            if(parsed.getKeys().isEmpty()||parsed.getKeys().size()>8)throw new IllegalArgumentException();
            for(var key:parsed.getKeys()) {
                if(!(key instanceof RSAKey rsa)||rsa.size()<2048||rsa.isPrivate()||rsa.getKeyID()==null
                        ||!rsa.getKeyID().matches("[A-Za-z0-9_-]{1,128}")||keys.put(rsa.getKeyID(),rsa)!=null
                        ||key.getAlgorithm()!=null&&!key.getAlgorithm().getName().equals("RS256")
                        ||key.getKeyUse()!=null&&!key.getKeyUse().getValue().equals("sig"))throw new IllegalArgumentException();
            }
            budget.check();entry.keys=Map.copyOf(keys);entry.expires=clock.instant().plusSeconds(60);
            RSAKey key=entry.keys.get(kid);
            if(key==null)throw ServiceFailure.denied(401,"SERVICE_TOKEN_INVALID");
            return key;
        }catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw ServiceFailure.unavailable("CALL_CANCELLED",caller,false);
        }catch(ServiceFailure error){throw error;}
        catch(Exception error){throw ServiceFailure.unavailable("SERVICE_JWKS_UNAVAILABLE",caller,false);}
        finally{if(locked)entry.lock.unlock();}
    }
}
