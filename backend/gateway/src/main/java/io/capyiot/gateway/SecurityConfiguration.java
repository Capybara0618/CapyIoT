package io.capyiot.gateway;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
class SecurityConfiguration {
    static final String ISSUER = "capyiot-gateway";

    @Bean
    SecretKey jwtKey(DemoAuthProperties properties) {
        byte[] bytes = Base64.getDecoder().decode(properties.jwtSecret());
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 random bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey key) {
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/login", "/api/v1/device-sessions", "/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").denyAll()
                        .anyExchange().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, error) ->
                                ApiErrors.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((exchange, error) ->
                                ApiErrors.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问")))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((exchange, error) ->
                                ApiErrors.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "登录已过期，请重新登录")))
                .build();
    }
}
