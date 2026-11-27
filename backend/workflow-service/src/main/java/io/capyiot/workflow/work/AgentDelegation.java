package io.capyiot.workflow.work;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.Jwt;

/** This identity is accepted only for the one explicitly confirmed command and its receipt. */
public final class AgentDelegation {
  private AgentDelegation() {}

  public record Binding(
      String actorUserId,
      String confirmationId,
      String draftId,
      long draftRevision,
      String requestHash,
      String idempotencyKey) {}

  public static boolean isAgent(Jwt jwt) {
    return "capyiot-agent-service".equals(jwt.getClaimAsString("iss"));
  }

  private static String id(Jwt jwt, String name) {
    String value = jwt.getClaimAsString(name);
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw denied();
    return value;
  }

  public static Binding validate(
      Jwt jwt, String method, String path, String key, boolean legacyActive) {
    if (!isAgent(jwt)
        || !"capyiot-agent-service".equals(jwt.getSubject())
        || !"CREATE_WORK_ORDER".equals(jwt.getClaimAsString("command"))) throw denied();
    Instant issued = jwt.getIssuedAt(), expiry = jwt.getExpiresAt(), now = Instant.now();
    if (issued == null
        || expiry == null
        || !expiry.isAfter(now)
        || issued.isAfter(now.plusSeconds(5))
        || !expiry.isAfter(issued)
        || expiry.isAfter(issued.plusSeconds(120))
        || jwt.getId() == null) throw denied();
    if (!java.util.List.of("capyiot-workflow").equals(jwt.getAudience())
        && !(legacyActive && java.util.List.of("capyiot-core").equals(jwt.getAudience())))
      throw denied();
    String kid = Objects.toString(jwt.getHeaders().get("kid"), "");
    if (!kid.matches("[A-Za-z0-9_-]{1,128}")) throw denied();
    Object rev = jwt.getClaim("draftRevision");
    if (!(rev instanceof Number number)
        || number.longValue() < 1
        || number.doubleValue() != number.longValue()) throw denied();
    String hash = jwt.getClaimAsString("requestHash"),
        commandKey = jwt.getClaimAsString("idempotencyKey");
    if (hash == null
        || !hash.matches("[a-f0-9]{64}")
        || commandKey == null
        || !commandKey.matches("agent-[A-Za-z0-9_-]{1,64}")
        || !commandKey.equals(key)) throw denied();
    boolean create = method.equals("POST") && path.equals("/api/v1/workorders");
    boolean receipt =
        method.equals("GET") && path.equals("/api/v1/workorders/commands/" + commandKey);
    if (!create && !receipt) throw denied();
    return new Binding(
        id(jwt, "actorUserId"),
        id(jwt, "confirmationId"),
        id(jwt, "draftId"),
        number.longValue(),
        hash,
        commandKey);
  }

  public static void verifyBody(HttpServletRequest request) {
    if (!(request.getAttribute("capyiot.delegation") instanceof Binding binding)) return;
    if (!(request.getAttribute("capyiot.command-body") instanceof byte[] body)) throw denied();
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      if (!hash.equals(binding.requestHash())) throw denied();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static ApiProblem denied() {
    return new ApiProblem(403, "DELEGATION_INVALID", "服务委托范围或请求绑定无效");
  }
}
