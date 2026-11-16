package io.capyiot.gateway;

import io.capyiot.contract.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class AuthController {
    private final DemoAuthProperties properties;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;

    AuthController(DemoAuthProperties properties, PasswordEncoder passwords, JwtEncoder tokens) {
        this.properties = properties;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/auth/login")
    Mono<ApiResponse<LoginResult>> login(@Valid @RequestBody LoginRequest request, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> authenticate(request, exchange.getRequest().getId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ApiResponse<LoginResult> authenticate(LoginRequest request, String traceId) {
        var account = properties.operator();
        String role = "OPERATOR";
        if (properties.admin().username().equals(request.username())) {
            account = properties.admin();
            role = "ADMIN";
        }
        boolean validPassword = passwords.matches(request.password(), account.passwordHash());
        if (!account.username().equals(request.username()) || !validPassword) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(3600);
        var user = new UserInfo(account.username(), role, properties.campusId());
        var claims = JwtClaimsSet.builder()
                .issuer(SecurityConfiguration.ISSUER)
                .subject(user.username())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .claim("campusId", user.campusId())
                .build();
        String token = tokens.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ApiResponse.ok(new LoginResult(token, "Bearer", expiresAt, user), traceId);
    }

    record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String password) {}
    record UserInfo(String username, String role, String campusId) {}
    record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserInfo user) {}
}
