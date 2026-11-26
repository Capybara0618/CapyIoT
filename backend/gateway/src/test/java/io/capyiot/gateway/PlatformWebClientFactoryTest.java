package io.capyiot.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class PlatformWebClientFactoryTest {
    @Test void plaintextIsRejectedOutsideExplicitLoopbackTestMode(){
        var strict=new PlatformWebClientFactory("unused-test-ca",false);
        assertThatThrownBy(()->strict.create(WebClient.builder(),"http://platform-service","test-key")).isInstanceOf(IllegalArgumentException.class);
        var localOnly=new PlatformWebClientFactory("unused-test-ca",true);
        assertThatThrownBy(()->localOnly.create(WebClient.builder(),"http://platform-service","test-key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->localOnly.create(WebClient.builder(),"https://user:password@platform-service","test-key")).isInstanceOf(IllegalArgumentException.class);
    }
}
