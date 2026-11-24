package io.capyiot.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.*;

/** Gateway alone owns private keys. First key is active; remaining keys verify existing sessions. */
@Component
class UserKeyRing {
    private final Path file;
    UserKeyRing(@Value("${capyiot.auth.key-file}") String path,@Value("${capyiot.auth.generate-key:false}") boolean generate) throws Exception {
        file=Path.of(path).toAbsolutePath();
        if(!Files.exists(file)&&generate) {
            Files.createDirectories(file.getParent());
            RSAKey key=new RSAKeyGenerator(3072).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).generate();
            try { Files.writeString(file,new JWKSet(key).toString(false),StandardOpenOption.CREATE_NEW); }
            catch(FileAlreadyExistsException ignored) { /* another replica provisioned the shared volume */ }
            try { Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); }
            catch(UnsupportedOperationException ignored) { /* Windows uses workspace ACLs */ }
        }
        keys();
    }
    JWKSet keys() {
        try {
            JWKSet keys=JWKSet.parse(Files.readString(file));
            if(keys.getKeys().isEmpty()||!(keys.getKeys().getFirst() instanceof RSAKey active)||!active.isPrivate()) throw new IllegalArgumentException("Active Gateway key must be a private RSA key");
            Set<String> ids=new HashSet<>();
            for(JWK key:keys.getKeys()) if(!(key instanceof RSAKey rsa)||rsa.size()<2048||key.getKeyID()==null||!ids.add(key.getKeyID())) throw new IllegalArgumentException("Invalid RSA key ring");
            return keys;
        } catch(IOException|ParseException error) { throw new IllegalStateException("Cannot load Gateway signing keys",error); }
    }
    String activeId() { return keys().getKeys().getFirst().getKeyID(); }
    Map<String,Object> publicKeys() { return keys().toPublicJWKSet().toJSONObject(); }
    JwtEncoder encoder() { return new NimbusJwtEncoder((selector,context)->selector.select(keys())); }
    Jwt decode(String value) {
        try {
            var signed=com.nimbusds.jwt.SignedJWT.parse(value);
            JWK found=keys().getKeyByKeyId(signed.getHeader().getKeyID());
            if(!(found instanceof RSAKey rsa)||!com.nimbusds.jose.JWSAlgorithm.RS256.equals(signed.getHeader().getAlgorithm())) throw new BadJwtException("Unsupported signing key");
            var decoder=NimbusJwtDecoder.withPublicKey(rsa.toRSAPublicKey()).build();
            decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(SecurityConfiguration.ISSUER),
                    new JwtClaimValidator<List<String>>("aud",aud->aud!=null&&aud.contains("capyiot-api"))));
            Jwt jwt=decoder.decode(value);
            if(jwt.getSubject()==null||jwt.getClaimAsString("sid")==null||jwt.getClaim("authz_ver")==null||jwt.getId()==null) throw new BadJwtException("Missing identity claims");
            return jwt;
        } catch(ParseException|JOSEException error) { throw new BadJwtException("Invalid user token",error); }
    }
}
