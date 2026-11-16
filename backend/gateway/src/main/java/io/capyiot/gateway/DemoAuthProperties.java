package io.capyiot.gateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("capyiot.auth")
public record DemoAuthProperties(
        @NotBlank @Size(min = 43) String jwtSecret,
        @NotBlank String campusId,
        @Valid Account admin,
        @Valid Account operator) {
    public record Account(@NotBlank String username, @NotBlank String passwordHash) {}
}
