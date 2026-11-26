package io.capyiot.gateway;

import java.util.ArrayList;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Apply the same CA to routed platform APIs, not just the login/context WebClient. */
@Configuration
@ConditionalOnProperty(name="capyiot.auth.platform-http-test-only",havingValue="false",matchIfMissing=true)
class PlatformProxyTrust {
    @Bean static BeanPostProcessor platformProxyCertificates(Environment environment){return new BeanPostProcessor(){
        public Object postProcessAfterInitialization(Object bean,String name){
            if(bean instanceof HttpClientProperties properties){
                String ca=environment.getProperty("capyiot.auth.platform-ca-file","/run/secrets/internal-ca.pem");
                var certificates=new ArrayList<>(properties.getSsl().getTrustedX509Certificates());
                if(!certificates.contains(ca))certificates.add(ca);
                properties.getSsl().setTrustedX509Certificates(certificates);properties.getSsl().setUseInsecureTrustManager(false);
            }return bean;
        }
    };}
}
