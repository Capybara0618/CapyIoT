package io.capyiot.core.platform;

import io.capyiot.contract.ApiResponse;
import io.capyiot.core.ApiProblem;
import io.capyiot.core.ports.DeviceQueryPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="capyiot.workorders.enabled",havingValue="true")
class AssignmentController {
    private final PlatformService platform;private final DeviceQueryPort devices;
    AssignmentController(PlatformService platform,DeviceQueryPort devices){this.platform=platform;this.devices=devices;}
    @GetMapping("/api/v1/workorder-assignees")
    ApiResponse<?> candidates(@AuthenticationPrincipal Jwt jwt,@RequestParam List<String> deviceIds,HttpServletRequest request){
        if(deviceIds.isEmpty()||deviceIds.size()>100)throw ApiProblem.invalid("请选择 1–100 台维修设备");
        var actor=platform.current(jwt);actor.require("workorder.assign");
        var refs=devices.requireVisibleDevices(actor,"workorder.assign",deviceIds);
        return ApiResponse.ok(platform.assignmentCandidates(refs),request.getRequestId());
    }
}
