package io.capyiot.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class WorkflowSecurity {
    @Bean
    JwtDecoder tokens(@Value("${capyiot.user-jwt-secret}") String secret,
                      @Value("${USER_JWKS_URI:}") String jwks,
                      @Value("${CORE_SERVICE_URL:http://localhost:8085}") String core,
                      @Value("${CORE_SERVICE_KEY:}") String serviceKey,
                      @Value("${capyiot.legacy-auth-enabled:false}") boolean legacy) {
        if(!jwks.isBlank()) return new io.capyiot.security.LiveUserJwtDecoder(jwks,core,serviceKey);
        if(!legacy) throw new IllegalStateException("User JWKS configuration is required; HMAC is a legacy test fixture only");
        byte[] key = Base64.getDecoder().decode(secret);
        if (key.length < 32) throw new IllegalArgumentException("User JWT key is too short");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("capyiot-gateway"));
        return decoder;
    }

    @Bean
    SecurityFilterChain workorders(HttpSecurity http, ObjectMapper json) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/workorders/dead-letters", "/api/v1/workorders/dead-letters/**",
                                "/api/v1/workorders/outbox/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/workorders", "/api/v1/workorders/**").hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.accessDeniedHandler((req, res, error) -> {
                    res.setStatus(403);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    json.writeValue(res.getOutputStream(), Map.of("code", "FORBIDDEN", "message", "无权执行此操作"));
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    OpenAPI workflowContract() {
        return new OpenAPI().info(new Info().title("CapyIoT Work Order API").version("1"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
