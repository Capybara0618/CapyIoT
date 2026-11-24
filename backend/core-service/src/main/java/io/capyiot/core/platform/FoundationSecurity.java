package io.capyiot.core.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.core.ApiProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class FoundationSecurity {
    @Bean
    JwtDecoder userTokens(@Value("${capyiot.foundation.jwks-uri}") String uri) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(uri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("capyiot-gateway"),
                new JwtClaimValidator<java.util.List<String>>("aud",aud->aud!=null&&aud.contains("capyiot-api")),
                new JwtClaimValidator<String>("sub",sub->sub!=null&&!sub.isBlank())));
        return decoder;
    }
    @Bean
    SecurityFilterChain coreSecurity(HttpSecurity http,PlatformService platform,ObjectMapper json,@Value("${capyiot.foundation.service-key}") String serviceKey) throws Exception {
        if(serviceKey.length()<32) throw new IllegalArgumentException("CORE_SERVICE_KEY requires at least 32 characters");
        var fresh=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                try {
                    if(req.getRequestURI().startsWith("/internal/")) {
                        String provided=req.getHeader("X-Core-Service-Key");
                        if(provided==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8))) throw new ApiProblem(401,"SERVICE_UNAUTHORIZED","内部服务身份无效");
                    }
                    var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null&&auth.getPrincipal() instanceof Jwt jwt&&!req.getRequestURI().equals("/api/v1/auth/logout")) req.setAttribute("capyiot.actor",platform.current(jwt));
                    chain.doFilter(req,res);
                } catch(ApiProblem error) {
                    res.setStatus(error.status()); res.setContentType("application/json;charset=UTF-8");
                    json.writeValue(res.getOutputStream(),Map.of("code",error.code(),"message",error.getMessage(),"details",Map.of(),"traceId",req.getRequestId()));
                }
            }
        };
        return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**","/actuator/prometheus","/internal/foundation/login",
                                "/internal/devices/**","/internal/ingestion/token","/api/v1/device-sessions","/.well-known/device-jwks.json").permitAll()
                        .requestMatchers("/actuator/**").denyAll().anyRequest().authenticated())
                .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((req,res,error)->write(json,req,res,401,"TOKEN_INVALID")))
                .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->write(json,req,res,401,"UNAUTHORIZED"))
                        .accessDeniedHandler((req,res,error)->write(json,req,res,403,"PERMISSION_DENIED")))
                .addFilterBefore(fresh,AnonymousAuthenticationFilter.class).build();
    }
    private static void write(ObjectMapper json,HttpServletRequest req,HttpServletResponse res,int status,String code) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getOutputStream(),Map.of("code",code,"message",status==401?"请重新登录":"无权访问","details",Map.of(),"traceId",req.getRequestId()));
    }
}
