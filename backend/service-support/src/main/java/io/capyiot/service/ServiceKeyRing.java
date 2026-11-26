package io.capyiot.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

/** Existing RSA/JWKS format. Reads mounted keys; never generates, logs or writes private material. */
public final class ServiceKeyRing {
    private final Path path;
    public ServiceKeyRing(Path path){this.path=path.toAbsolutePath();read();}
    private JWKSet read() {
        try {
            if(Files.size(path)>65536)throw new IllegalArgumentException();
            var keys=JWKSet.parse(Files.readString(path));
            var ids=new HashSet<String>();
            if(keys.getKeys().isEmpty()||keys.getKeys().size()>8)throw new IllegalArgumentException();
            for(var key:keys.getKeys()) {
                if(!(key instanceof RSAKey rsa)||rsa.size()<2048||key.getKeyID()==null
                        ||!key.getKeyID().matches("[A-Za-z0-9_-]{1,128}")||!ids.add(key.getKeyID())
                        ||key.getAlgorithm()!=null&&!key.getAlgorithm().getName().equals("RS256")
                        ||key.getKeyUse()!=null&&!key.getKeyUse().getValue().equals("sig"))throw new IllegalArgumentException();
            }
            if(!((RSAKey)keys.getKeys().getFirst()).isPrivate())throw new IllegalArgumentException();
            return keys;
        }catch(Exception error){throw new IllegalStateException("Service signing key ring unavailable");}
    }
    RSAKey active(){return (RSAKey)read().getKeys().getFirst();}
    public Map<String,Object> publicJwks(){return read().toPublicJWKSet().toJSONObject();}
    @Override public String toString(){return "ServiceKeyRing[REDACTED]";}
}
