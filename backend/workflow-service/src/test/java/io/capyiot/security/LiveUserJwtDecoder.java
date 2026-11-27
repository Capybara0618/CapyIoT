package io.capyiot.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestClient;

/** Temporary bridge: old controllers may serve only complete PARK grants, never partial scopes. */
public final class LiveUserJwtDecoder implements JwtDecoder {
  private final NimbusJwtDecoder signatures;
  private final RestClient core;

  public LiveUserJwtDecoder(String jwksUri, String coreUrl, String serviceKey) {
    if (serviceKey.length() < 32) throw new IllegalArgumentException("Core service key is missing");
    signatures =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    signatures.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("capyiot-gateway"),
            new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains("capyiot-api"))));
    var requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(Duration.ofSeconds(2));
    requests.setReadTimeout(Duration.ofSeconds(3));
    core =
        RestClient.builder()
            .baseUrl(coreUrl)
            .requestFactory(requests)
            .defaultHeader("X-Core-Service-Key", serviceKey)
            .build();
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    Jwt jwt = signatures.decode(token);
    try {
      JsonNode context =
          core.get()
              .uri("/internal/foundation/context")
              .headers(h -> h.setBearerAuth(token))
              .retrieve()
              .body(JsonNode.class);
      if (context == null || !jwt.getSubject().equals(context.path("userId").asText()))
        throw new BadJwtException("Identity mismatch");
      Set<String> roles = new HashSet<>();
      for (JsonNode grant : context.path("grants")) {
        if (!grant.path("scopeType").asText().equals("PARK")
            || !grant.path("parkId").asText().equals("CAMPUS-A")) continue;
        switch (grant.path("roleCode").asText()) {
          case "OPS_SUPERVISOR" -> roles.add("ADMIN");
          case "OPS_OPERATOR" -> roles.add("OPERATOR");
          default -> {}
        }
      }
      var claims = new HashMap<>(jwt.getClaims());
      claims.put("roles", List.copyOf(roles));
      claims.put("campusId", "CAMPUS-A");
      claims.put("legacyLoginName", context.path("loginName").asText());
      return new Jwt(
          jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getHeaders(), claims);
    } catch (JwtException error) {
      throw error;
    } catch (Exception error) {
      throw new BadJwtException("Current authorization unavailable");
    }
  }

  /**
   * Old work-order assignments store login names. Target APIs continue using stable JWT subject
   * IDs.
   */
  public static String legacyLoginName(Jwt jwt) {
    String name = jwt.getClaimAsString("legacyLoginName");
    return name == null || name.isBlank() ? jwt.getSubject() : name;
  }
}
