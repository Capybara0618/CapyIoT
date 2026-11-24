package io.capyiot.core.asset;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class DeviceSigningKeys {
    private final Path file;
    DeviceSigningKeys(@Value("${capyiot.device-key-file:../var/device-keys.json}") String file,
                       @Value("${capyiot.device-keys-generate:false}") boolean generate) throws Exception {
        this.file=Path.of(file).toAbsolutePath();
        if(!Files.exists(this.file)&&generate) {
            Files.createDirectories(this.file.getParent());
            var key=new RSAKeyGenerator(3072).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).generate();
            try{Files.writeString(this.file,new JWKSet(key).toString(false),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
            try{Files.setPosixFilePermissions(this.file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        }
        keys();
    }
    JWKSet keys() {
        try {
            var set=JWKSet.parse(Files.readString(file));
            if(set.getKeys().isEmpty()||!(set.getKeys().getFirst() instanceof RSAKey rsa)||!rsa.isPrivate()||rsa.size()<2048)throw new IllegalStateException("Device RSA signing key unavailable");
            Set<String> ids=new HashSet<>();for(var key:set.getKeys())if(!(key instanceof RSAKey r)||r.size()<2048||key.getKeyID()==null||!ids.add(key.getKeyID()))throw new IllegalStateException("Invalid device key ring");
            return set;
        }catch(Exception error){throw new IllegalStateException("Cannot read device signing keys",error);}
    }
    Map<String,Object> publicKeys(){return keys().toPublicJWKSet().toJSONObject();}
    String sign(JwtClaimsSet claims){var set=keys();return new NimbusJwtEncoder((selector,ctx)->selector.select(set)).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(set.getKeys().getFirst().getKeyID()).build(),claims)).getTokenValue();}
}
