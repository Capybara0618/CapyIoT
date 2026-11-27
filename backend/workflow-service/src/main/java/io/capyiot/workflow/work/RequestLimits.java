package io.capyiot.workflow.work;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RequestLimits {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedUploads() {
    return factory ->
        factory.addConnectorCustomizers(
            connector -> {
              if (connector.getProtocolHandler()
                  instanceof org.apache.coyote.http11.AbstractHttp11Protocol<?> protocol) {
                protocol.setConnectionTimeout(3000);
                protocol.setConnectionUploadTimeout(3000);
                protocol.setDisableUploadTimeout(false);
              }
            });
  }
}
