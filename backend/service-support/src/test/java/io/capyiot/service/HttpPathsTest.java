package io.capyiot.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpPathsTest {
    @Test void contextPrefixCannotHideAnInternalEndpoint(){
        assertThat(HttpPaths.canonical("/platform/internal/v1/identity/login","/platform")).isEqualTo("/internal/v1/identity/login");
    }
    @Test void encodedAndMatrixPathsCannotSkipServiceAuthentication(){
        for(String path:java.util.List.of("/%69nternal/v1/identity/login","/internal;alias/v1/identity/login","/internal//v1/login","/internal/../v1/login"))
            assertThatThrownBy(()->HttpPaths.canonical(path,"")).isInstanceOf(ServiceFailure.class);
    }
}
