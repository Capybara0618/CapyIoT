package io.capyiot.gateway;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Dedicated platform TLS trust; no trust-all manager and no shared mutable WebClient builder. */
@Component
class PlatformWebClientFactory {
    private final Path ca;private final boolean localHttpTestOnly;
    PlatformWebClientFactory(@Value("${capyiot.auth.platform-ca-file:/run/secrets/internal-ca.pem}") String ca,
            @Value("${capyiot.auth.platform-http-test-only:false}") boolean testOnly){this.ca=Path.of(ca);this.localHttpTestOnly=testOnly;}
    WebClient create(WebClient.Builder template,String url,String legacyKey) {
        URI uri=URI.create(url);
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!java.util.Set.of("","/").contains(uri.getPath()))
            throw new IllegalArgumentException("Invalid platform origin");
        var client=HttpClient.create().followRedirect(false).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,1000).responseTimeout(Duration.ofSeconds(3));
        if(uri.getScheme().equals("https"))try{
            var ssl=SslContextBuilder.forClient().trustManager(ca.toFile()).protocols("TLSv1.3","TLSv1.2").build();
            client=client.secure(spec->spec.sslContext(ssl));
        }catch(Exception error){throw new IllegalStateException("Platform TLS trust unavailable");}
        else if(!localHttpTestOnly||!uri.getScheme().equals("http")||!java.util.Set.of("127.0.0.1","localhost").contains(uri.getHost()))
            throw new IllegalArgumentException("Platform service requires TLS");
        return template.clone().baseUrl(url).clientConnector(new ReactorClientHttpConnector(client))
                .defaultHeader("X-Core-Service-Key",legacyKey).build();
    }
}
