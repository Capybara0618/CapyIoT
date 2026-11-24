package io.capyiot.contract;

import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;

public final class LocalTls {
    private LocalTls() {}
    public static SSLSocketFactory sockets(String caFile) {
        try(var input=Files.newInputStream(Path.of(caFile))) {
            var certificate=CertificateFactory.getInstance("X.509").generateCertificate(input);
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("capyiot-local-ca",certificate);
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            var ssl=SSLContext.getInstance("TLS");ssl.init(null,trust.getTrustManagers(),new java.security.SecureRandom());return ssl.getSocketFactory();
        }catch(Exception error){throw new IllegalStateException("Cannot load configured TLS trust anchor",error);}
    }
}
