package io.capyiot.device;

import static io.capyiot.device.DeviceModels.*;

import io.capyiot.contract.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class DeviceController {
    private final DeviceAssets assets;
    private final DeviceQueries queries;
    private final DeviceSessions sessions;

    DeviceController(DeviceAssets assets, DeviceQueries queries, DeviceSessions sessions) {
        this.assets = assets;
        this.queries = queries;
        this.sessions = sessions;
    }

    @GetMapping("/devices")
    ApiResponse<Page<DeviceView>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String type,
            @RequestParam(required = false) String buildingId, @RequestParam(required = false) String connectivity,
            @RequestParam(required = false) String health, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(queries.list(campus(jwt), type, buildingId, connectivity, health, keyword, page, size));
    }

    @GetMapping("/devices/summary")
    ApiResponse<Summary> summary(@AuthenticationPrincipal Jwt jwt) { return ok(queries.summary(campus(jwt))); }

    @GetMapping("/devices/{id}")
    ApiResponse<DeviceView> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return ok(queries.find(campus(jwt), id)); }

    @PostMapping("/devices")
    ApiResponse<Registration> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDevice request) {
        return ok(assets.create(campus(jwt), request));
    }

    @PatchMapping("/devices/{id}")
    ApiResponse<DeviceView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody UpdateDevice request) {
        return ok(assets.update(campus(jwt), id, request));
    }

    @GetMapping("/locations")
    ApiResponse<?> locations(@AuthenticationPrincipal Jwt jwt) { return ok(assets.locations(campus(jwt))); }

    @PostMapping("/locations/{kind}")
    ApiResponse<?> location(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind, @Valid @RequestBody LocationRequest request) {
        return ok(assets.createLocation(campus(jwt), kind, request));
    }

    @GetMapping("/topology/snapshot")
    ApiResponse<?> topology(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String buildingId) {
        return ok(assets.topology(campus(jwt), buildingId));
    }

    @PostMapping("/topology/links")
    ApiResponse<?> link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) { return ok(assets.link(campus(jwt), request)); }

    @PostMapping("/device-sessions")
    @SecurityRequirements
    ApiResponse<SessionResult> session(@Valid @RequestBody SessionRequest request) { return ok(sessions.open(request)); }

    static String campus(Jwt jwt) {
        String campus = jwt.getClaimAsString("campusId");
        if (campus == null || campus.isBlank()) throw new DeviceProblem(org.springframework.http.HttpStatus.FORBIDDEN, "CAMPUS_REQUIRED", "账号缺少园区权限");
        return campus;
    }

    static <T> ApiResponse<T> ok(T data) { return ApiResponse.ok(data, UUID.randomUUID().toString()); }
}
