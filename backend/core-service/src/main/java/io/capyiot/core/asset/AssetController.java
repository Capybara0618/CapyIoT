package io.capyiot.core.asset;

import static io.capyiot.core.asset.AssetModels.*;
import io.capyiot.contract.ApiResponse;
import io.capyiot.core.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
@RequestMapping("/api/v1")
class AssetController {
    private final AssetService assets;
    private final PlatformService platform;
    AssetController(AssetService assets,PlatformService platform) { this.assets=assets;this.platform=platform; }
    private <T> ApiResponse<T> ok(T data,HttpServletRequest request) { return ApiResponse.ok(data,request.getRequestId()); }
    @GetMapping("/parks") ApiResponse<?> parks(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req) { return ok(assets.parks(platform.current(jwt)),req); }
    @GetMapping("/areas") ApiResponse<?> areas(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="CAMPUS-A") String parkId,@RequestParam(required=false) String parentAreaId,HttpServletRequest req) { return ok(assets.areas(platform.current(jwt),parkId,parentAreaId),req); }
    @PostMapping("/areas") ApiResponse<?> area(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateArea body,HttpServletRequest req) { return ok(assets.createArea(platform.current(jwt),key,body),req); }
    @PatchMapping("/areas/{id}") ApiResponse<?> area(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UpdateArea body,HttpServletRequest req) { return ok(assets.updateArea(platform.current(jwt),key,id,body),req); }
    @GetMapping("/devices") ApiResponse<?> devices(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) DeviceType deviceType,@RequestParam(required=false) String areaId,
        @RequestParam(defaultValue="SUBTREE") AreaScope areaScope,@RequestParam(required=false) AssetStatus assetStatus,@RequestParam(required=false) Connectivity connectivityStatus,
        @RequestParam(required=false) OperatingMode operatingMode,@RequestParam(required=false) Freshness dataFreshness,@RequestParam(required=false) String keyword,@RequestParam(required=false) String monitoringPointId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req) {
        return ok(assets.devices(platform.current(jwt),name(deviceType),areaId,areaScope.name(),name(assetStatus),name(connectivityStatus),name(operatingMode),name(dataFreshness),keyword,monitoringPointId,page,size),req);
    }
    enum AreaScope { EXACT,SUBTREE }
    enum Connectivity { UNKNOWN,ONLINE,OFFLINE }
    enum OperatingMode { UNKNOWN,NORMAL,LOW_POWER }
    enum Freshness { NO_DATA,FRESH,STALE }
    private static String name(Enum<?> value) { return value==null?null:value.name(); }
    @GetMapping("/devices/{id}") ApiResponse<?> device(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req) { return ok(assets.detail(platform.current(jwt),id),req); }
    @PostMapping("/devices") ApiResponse<?> device(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateDevice body,HttpServletRequest req) { return ok(assets.createDevice(platform.current(jwt),key,body),req); }
    @PatchMapping("/devices/{id}") ApiResponse<?> device(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UpdateDevice body,HttpServletRequest req) { return ok(assets.updateDevice(platform.current(jwt),key,id,body),req); }
    @GetMapping("/monitoring-points") ApiResponse<?> points(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="CAMPUS-A") String parkId,HttpServletRequest req) { return ok(assets.points(platform.current(jwt),parkId),req); }
    @PostMapping("/monitoring-points") ApiResponse<?> point(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreatePoint body,HttpServletRequest req) { return ok(assets.createPoint(platform.current(jwt),key,body),req); }
    @PatchMapping("/monitoring-points/{id}") ApiResponse<?> point(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UpdatePoint body,HttpServletRequest req) { return ok(assets.updatePoint(platform.current(jwt),key,id,body),req); }
    @GetMapping("/monitoring-points/{id}/bindings") ApiResponse<?> bindings(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req) { return ok(assets.bindings(platform.current(jwt),null,id),req); }
    @PostMapping("/monitoring-points/{id}/bindings") ApiResponse<?> bind(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BindPoint body,HttpServletRequest req) { return ok(assets.bind(platform.current(jwt),key,id,body),req); }
    @PostMapping("/network-dependencies") ApiResponse<?> network(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Network body,HttpServletRequest req) { return ok(assets.network(platform.current(jwt),key,body),req); }
    @PostMapping("/power-dependencies") ApiResponse<?> power(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Power body,HttpServletRequest req) { return ok(assets.power(platform.current(jwt),key,body),req); }
    @PostMapping("/video-channel-associations") ApiResponse<?> video(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Video body,HttpServletRequest req) { return ok(assets.video(platform.current(jwt),key,body),req); }
    @PostMapping("/{kind:network-dependencies|power-dependencies|video-channel-associations|monitoring-point-bindings}/{id}/end")
    ApiResponse<?> end(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody EndRelation body,HttpServletRequest req) { return ok(assets.end(platform.current(jwt),key,kind,id,body),req); }
    @GetMapping("/topology/context") ApiResponse<?> topology(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String deviceId,@RequestParam(required=false) String monitoringPointId,
        @RequestParam(required=false) Instant asOf,@RequestParam(defaultValue="2") int depth,HttpServletRequest req) { return ok(assets.topology(platform.current(jwt),deviceId,monitoringPointId,asOf==null?Instant.now():asOf,depth),req); }
    @GetMapping("/devices/{id}/status-history") ApiResponse<?> history(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam(required=false) String stateDimension,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req) { return ok(assets.history(platform.current(jwt),id,stateDimension,from,to,page,size),req); }
}
