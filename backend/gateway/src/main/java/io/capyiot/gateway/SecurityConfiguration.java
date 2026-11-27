package io.capyiot.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration
class SecurityConfiguration {
  static final String ISSUER = "capyiot-gateway";

  @Bean
  JwtEncoder jwtEncoder(UserKeyRing keys) {
    return keys.encoder();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(UserKeyRing keys) {
    return token ->
        Mono.fromCallable(() -> keys.decode(token)).subscribeOn(Schedulers.boundedElastic());
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http, CurrentAuthorization current) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .addFilterAfter(
            current::filter,
            org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION)
        .authorizeExchange(
            a ->
                a.pathMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/device-sessions",
                        "/.well-known/jwks.json",
                        "/.well-known/service-jwks.json",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus")
                    .permitAll()
                    .pathMatchers("/actuator/**", "/internal/**")
                    .denyAll()
                    .anyExchange()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (x, error) ->
                            ApiErrors.write(x, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                    .accessDeniedHandler(
                        (x, error) ->
                            ApiErrors.write(x, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "无权访问")))
        .oauth2ResourceServer(
            o ->
                o.jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(
                        (x, error) ->
                            ApiErrors.write(
                                x, HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "登录已过期，请重新登录")))
        .build();
  }
}
