package io.capyiot.core.asset;

import io.capyiot.contract.ApiResponse;
import io.capyiot.core.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class AssetReadController {
    private final AssetService assets;private final PlatformService platform;
    AssetReadController(AssetService assets,PlatformService platform){this.assets=assets;this.platform=platform;}
    private <T> ApiResponse<T> ok(T body,HttpServletRequest req){return ApiResponse.ok(body,req.getRequestId());}
    @GetMapping("/health") ApiResponse<?> health(HttpServletRequest req){return ok(Map.of("service","core-service","status","UP","checkedAt",Instant.now()),req);}
    @GetMapping("/devices/summary") ApiResponse<?> summary(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){return ok(assets.overview(platform.current(jwt)),req);}
    @GetMapping({"/events","/devices/{id}/events"}) ApiResponse<?> events(@AuthenticationPrincipal Jwt jwt,@PathVariable(required=false) String id,
        @RequestParam(required=false) String deviceId,@RequestParam(required=false) String eventType,@RequestParam(required=false) String status,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size,HttpServletRequest req){return ok(assets.events(platform.current(jwt),id==null?deviceId:id,eventType,status,from,to,page,size),req);}
    @GetMapping("/events/{eventId}") ApiResponse<?> event(@AuthenticationPrincipal Jwt jwt,@PathVariable String eventId,HttpServletRequest req){return ok(assets.event(platform.current(jwt),eventId),req);}
}
