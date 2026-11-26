package io.capyiot.platform;

import io.capyiot.contract.ApiResponse;
import io.capyiot.contract.internal.PlatformApi;
import io.capyiot.service.ServiceFailure;
import io.capyiot.service.ServiceKeyRing;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class PlatformController {
    private final PlatformService platform;private final PlatformJournal commands;private final ObjectProvider<ServiceKeyRing> keys;
    PlatformController(PlatformService platform,PlatformJournal commands,ObjectProvider<ServiceKeyRing> keys){this.platform=platform;this.commands=commands;this.keys=keys;}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest req){return ApiResponse.ok(data,req.getRequestId());}
    @GetMapping("/.well-known/service-jwks.json") Map<String,Object> jwks(){var ring=keys.getIfAvailable();if(ring==null)throw ServiceFailure.unavailable("SERVICE_KEYS_UNAVAILABLE",null,false);return ring.publicJwks();}
    @PostMapping("/internal/v1/identity/login") ApiResponse<?> login(@RequestBody PlatformApi.LoginRequest command,HttpServletRequest req) {
        return ok(platform.login(new PlatformService.LoginCommand(command.loginName(),command.password())),req);
    }
    @PostMapping("/internal/v1/identity/context") ApiResponse<?> context(@AuthenticationPrincipal Jwt jwt,@RequestBody PlatformApi.IdentityRequest request,HttpServletRequest req){return ok(platform.identity(platform.current(jwt)),req);}
    @PostMapping("/internal/v1/users/eligibility") ApiResponse<?> eligibility(@AuthenticationPrincipal Jwt jwt,@RequestBody PlatformApi.EligibilityRequest body,HttpServletRequest req){return ok(platform.eligibility(platform.current(jwt),body),req);}
    @GetMapping("/api/v1/auth/me") ApiResponse<?> me(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){return ok(platform.me(platform.current(jwt)),req);}
    @PostMapping("/api/v1/auth/logout") ApiResponse<?> logout(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){platform.logout(jwt);return ok(Map.of("revoked",true),req);}
    @GetMapping("/api/v1/users") ApiResponse<?> users(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){return ok(platform.users(platform.current(jwt)),req);}
    @GetMapping("/api/v1/users/{id}") ApiResponse<?> user(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req){return ok(platform.user(platform.current(jwt),id),req);}
    @PostMapping("/api/v1/users") org.springframework.http.ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PlatformService.CreateUser body,HttpServletRequest req) {
        var actor=platform.current(jwt);actor.requirePlatform("platform.user.manage");var result=commands.execute(key,actor.userId(),"user.create",body,()->platform.createUser(actor,body));
        return org.springframework.http.ResponseEntity.status(result.path("created").asBoolean()?201:200).body(ok(result,req));
    }
    @PatchMapping("/api/v1/users/{id}") ApiResponse<?> update(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody PlatformService.UpdateUser body,HttpServletRequest req) {
        var actor=platform.current(jwt);actor.requirePlatform("platform.user.manage");return ok(commands.execute(key,actor.userId(),"user.update",Map.of("userId",id,"body",body),()->{platform.updateUser(actor,id,body);return Map.of("updated",true);}),req);
    }
    @PostMapping("/api/v1/users/{id}/grants") ApiResponse<?> grant(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody PlatformService.GrantCommand body,HttpServletRequest req) {
        var actor=platform.current(jwt);actor.requirePlatform("platform.grant.assign");var input=Map.of("userId",id,"body",body);
        var replay=commands.replay(key,actor.userId(),"grant.create",input);if(replay.isPresent())return ok(replay.get(),req);
        platform.validateGrant(body); // Before the command journal opens its local transaction/row lock.
        return ok(commands.execute(key,actor.userId(),"grant.create",input,()->platform.grantValidated(actor,id,body)),req);
    }
    record Revoke(@NotBlank String reason){}
    @PostMapping("/api/v1/grants/{id}/revoke") ApiResponse<?> revoke(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Revoke body,HttpServletRequest req) {
        var actor=platform.current(jwt);actor.requirePlatform("platform.grant.revoke");return ok(commands.execute(key,actor.userId(),"grant.revoke",Map.of("grantId",id,"body",body),()->{platform.revokeGrant(actor,id,body.reason());return Map.of("revoked",true);}),req);
    }
    @GetMapping("/api/v1/audit-records") ApiResponse<?> audit(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,
            @RequestParam(required=false) String actorUserId,@RequestParam(required=false) String resourceId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,HttpServletRequest req) {
        return ok(platform.auditPage(platform.current(jwt),page,size,actorUserId,resourceId,from,to),req);
    }
}

/** Only for the explicitly enabled, expiring migration window. No fallback on platform failure. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
class PlatformLegacyController {
    private final PlatformService platform;
    PlatformLegacyController(PlatformService platform){this.platform=platform;}
    @PostMapping("/internal/foundation/login") PlatformService.SessionIdentity login(@RequestBody PlatformService.LoginCommand body){return platform.login(body);}
    @GetMapping("/internal/foundation/context") AuthorizationContext context(@AuthenticationPrincipal Jwt jwt){return platform.current(jwt);}
    @PostMapping("/internal/foundation/eligibility") PlatformApi.EligibilityResult eligibility(@AuthenticationPrincipal Jwt jwt,@RequestBody PlatformApi.EligibilityRequest body){return platform.eligibility(platform.current(jwt),body);}
}
