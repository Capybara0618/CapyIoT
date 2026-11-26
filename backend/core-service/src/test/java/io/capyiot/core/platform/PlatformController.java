package io.capyiot.core.platform;

import io.capyiot.contract.ApiResponse;
import io.capyiot.core.CommandJournal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="capyiot.platform.test-fixture",havingValue="true")
class PlatformController {
    private final PlatformService platform;
    private final CommandJournal commands;
    PlatformController(PlatformService platform,org.springframework.jdbc.core.JdbcTemplate db,
            org.springframework.transaction.support.TransactionTemplate tx,com.fasterxml.jackson.databind.ObjectMapper json,
            @Value("${capyiot.foundation.service-key}") String key) { this.platform=platform; commands=new CommandJournal(db,tx,json,key); }
    private <T> ApiResponse<T> ok(T data,HttpServletRequest request) { return ApiResponse.ok(data,request.getRequestId()); }
    @PostMapping("/internal/foundation/login")
    PlatformService.SessionIdentity login(@RequestBody PlatformService.LoginCommand command) { return platform.login(command); }
    @GetMapping("/api/v1/auth/me")
    ApiResponse<?> me(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) { return ok(platform.me(platform.current(jwt)),request); }
    @GetMapping("/internal/foundation/context")
    AuthorizationContext context(@AuthenticationPrincipal Jwt jwt) { return platform.current(jwt); }
    @PostMapping("/api/v1/auth/logout")
    ApiResponse<?> logout(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) { platform.logout(jwt); return ok(Map.of("revoked",true),request); }
    @GetMapping("/api/v1/users")
    ApiResponse<?> users(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) { return ok(platform.users(platform.current(jwt)),request); }
    @GetMapping("/api/v1/users/{id}")
    ApiResponse<?> user(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest request) { return ok(platform.user(platform.current(jwt),id),request); }
    @PostMapping("/api/v1/users")
    org.springframework.http.ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PlatformService.CreateUser command,HttpServletRequest request) {
        var actor=platform.current(jwt); actor.requirePlatform("platform.user.manage");
        var result=commands.execute("PLATFORM",key,actor.userId(),"user.create",command,()->platform.createUser(actor,command));return org.springframework.http.ResponseEntity.status(result.path("created").asBoolean()?201:200).body(ok(result,request));
    }
    @PatchMapping("/api/v1/users/{id}")
    ApiResponse<?> update(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody PlatformService.UpdateUser command,HttpServletRequest request) {
        var actor=platform.current(jwt); actor.requirePlatform("platform.user.manage");
        return ok(commands.execute("PLATFORM",key,actor.userId(),"user.update",Map.of("userId",id,"body",command),()->{platform.updateUser(actor,id,command);return Map.of("updated",true);}),request);
    }
    @PostMapping("/api/v1/users/{id}/grants")
    ApiResponse<?> grant(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody PlatformService.GrantCommand command,HttpServletRequest request) {
        var actor=platform.current(jwt); actor.requirePlatform("platform.grant.assign");
        return ok(commands.execute("PLATFORM",key,actor.userId(),"grant.create",Map.of("userId",id,"body",command),()->platform.grant(actor,id,command)),request);
    }
    record Revoke(@NotBlank String reason) {}
    @PostMapping("/api/v1/grants/{id}/revoke")
    ApiResponse<?> revoke(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Revoke command,HttpServletRequest request) {
        var actor=platform.current(jwt); actor.requirePlatform("platform.grant.revoke");
        return ok(commands.execute("PLATFORM",key,actor.userId(),"grant.revoke",Map.of("grantId",id,"body",command),()->{platform.revokeGrant(actor,id,command.reason());return Map.of("revoked",true);}),request);
    }
    @GetMapping("/api/v1/audit-records")
    ApiResponse<?> audit(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,
        @RequestParam(required=false) String actorUserId,@RequestParam(required=false) String resourceId,
        @RequestParam(required=false) java.time.Instant from,@RequestParam(required=false) java.time.Instant to,HttpServletRequest request) { return ok(platform.auditPage(platform.current(jwt),page,size,actorUserId,resourceId,from,to),request); }
}

@Configuration
@ConditionalOnProperty(name="capyiot.platform.test-fixture",havingValue="true")
class PlatformBootstrap {
    @Bean
    @ConditionalOnProperty(name="capyiot.foundation.bootstrap.enabled",havingValue="true")
    ApplicationRunner seedPlatform(PlatformService platform,@Value("${capyiot.foundation.bootstrap.admin-hash}") String admin,
                                    @Value("${capyiot.foundation.bootstrap.operator-hash}") String operator) {
        return args->platform.seed(admin,operator);
    }
}
