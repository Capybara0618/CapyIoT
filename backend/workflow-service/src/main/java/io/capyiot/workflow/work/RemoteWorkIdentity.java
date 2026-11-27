package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import org.springframework.security.oauth2.jwt.Jwt;

final class RemoteWorkIdentity implements WorkIdentity {
  private final InternalHttpClient client;

  RemoteWorkIdentity(InternalHttpClient client) {
    this.client = client;
  }

  private record Cached(String digest, AuthorizationContext actor) {}

  public AuthorizationContext current(Jwt jwt) {
    var credential = ActorCredential.bearer("Bearer " + jwt.getTokenValue());
    var request = RequestCalls.request();
    if (request.getAttribute("capyiot.work-actor") instanceof Cached c
        && c.digest().equals(credential.digest())) return c.actor();
    var identity =
        client.call(
            InternalEndpoint.IDENTITY_CONTEXT,
            credential,
            new PlatformApi.IdentityRequest(),
            PlatformApi.Identity.class,
            RequestCalls.budget(),
            RequestCalls.correlation());
    boolean delegated = "capyiot-agent-service".equals(jwt.getClaimAsString("iss"));
    String user = delegated ? jwt.getClaimAsString("actorUserId") : jwt.getSubject();
    String session =
        delegated
            ? "delegation:" + jwt.getClaimAsString("confirmationId")
            : jwt.getClaimAsString("sid");
    if (!identity.userId().equals(user) || !identity.sessionId().equals(session))
      throw ApiProblem.forbidden();
    var actor =
        new AuthorizationContext(
            identity.userId(),
            identity.loginName(),
            identity.displayName(),
            identity.authorizationVersion(),
            identity.sessionId(),
            identity.grants().stream()
                .map(
                    g ->
                        new AuthorizationContext.Grant(
                            g.grantId(),
                            g.roleCode(),
                            g.scopeType().name(),
                            g.parkId(),
                            g.rootAreaIds(),
                            g.assignmentRelation() == null ? null : g.assignmentRelation().name()))
                .toList());
    if (!identity.permissionCodes().equals(actor.permissions()))
      throw ServiceFailure.unavailable("PERMISSION_CATALOG_MISMATCH", ServiceId.PLATFORM, false);
    request.setAttribute("capyiot.work-actor", new Cached(credential.digest(), actor));
    return actor;
  }

  public java.util.List<java.util.Map<String, Object>> assignmentCandidates(
      AuthorizationContext actor, java.util.List<DeviceQueryPort.DeviceRef> devices) {
    actor.require("workorder.assign");
    return personnel.candidates(devices);
  }

  private WorkPersonnel personnel;

  void personnel(WorkPersonnel personnel) {
    this.personnel = personnel;
  }
}
