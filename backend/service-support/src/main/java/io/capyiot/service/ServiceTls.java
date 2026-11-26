package io.capyiot.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class ServiceTls {
    private ServiceTls(){}
    public static SSLContext trust(Path caFile) {
        try {
            if(Files.size(caFile)>65536)throw new IllegalArgumentException();
            try(var input=Files.newInputStream(caFile)) {
                var certificates=CertificateFactory.getInstance("X.509").generateCertificates(input);
                if(certificates.isEmpty())throw new IllegalArgumentException();
                var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);int index=0;
                for(var certificate:certificates)store.setCertificateEntry("capyiot-ca-"+index++,certificate);
                var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
                var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),new java.security.SecureRandom());
                return context;
            }
        }catch(Exception error){throw new IllegalStateException("Service TLS trust anchors unavailable");}
    }
}
