package io.capyiot.device;

import static io.capyiot.device.DeviceController.campus;
import static io.capyiot.device.DeviceController.ok;

import io.capyiot.contract.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class ScenarioController {
    record Template(ScenarioRuns.Template id, int minimumSeconds, int defaultSeconds, int maximumSeconds) {}
    record Catalogue(List<Template> templates, ScenarioDevices.Catalogue scope, long heartbeatSeconds, long timeoutSeconds,
            long sweepSeconds) {}
    record Detail(ScenarioRuns.Run run, List<DeviceModels.DeviceView> targets, List<ScenarioRuns.Log> logs,
            List<DeviceEvents.EventView> evidence, Instant checkedAt) {}

    private final ScenarioRuns runs;
    private final ScenarioDevices devices;
    private final DeviceQueries queries;
    private final DeviceEvents events;
    private final long timeoutMs;
    private final long sweepMs;

    ScenarioController(ScenarioRuns runs, ScenarioDevices devices, DeviceQueries queries, DeviceEvents events,
            @org.springframework.beans.factory.annotation.Value("${capyiot.offline-timeout-ms:30000}") long timeoutMs,
            @org.springframework.beans.factory.annotation.Value("${capyiot.offline-sweep-ms:5000}") long sweepMs) {
        this.runs = runs;
        this.devices = devices;
        this.queries = queries;
        this.events = events;
        this.timeoutMs = timeoutMs;
        this.sweepMs = sweepMs;
    }

    @GetMapping("/scenarios/templates")
    ApiResponse<Catalogue> templates(@AuthenticationPrincipal Jwt jwt) {
        var templates = Arrays.stream(ScenarioRuns.Template.values())
                .map(item -> new Template(item, item.minimumSeconds, item.defaultSeconds, 300)).toList();
        return ok(new Catalogue(templates, devices.catalogue(campus(jwt)), 10, timeoutMs / 1000, sweepMs / 1000));
    }

    @GetMapping("/scenario-runs")
    ApiResponse<List<ScenarioRuns.Run>> recent(@AuthenticationPrincipal Jwt jwt) {
        return ok(runs.recent(campus(jwt)));
    }

    @PostMapping("/scenario-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<ScenarioRuns.Run> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScenarioRuns.Start params) {
        return ok(runs.start(campus(jwt), io.capyiot.security.LiveUserJwtDecoder.legacyLoginName(jwt), params));
    }

    @GetMapping("/scenario-runs/{id}")
    ApiResponse<Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        String campus = campus(jwt);
        var run = runs.find(campus, id);
        var targets = run.targetIds().stream().map(target -> queries.find(campus, target)).toList();
        return ok(new Detail(run, targets, runs.logs(id), events.scenarioEvidence(campus, id), Instant.now()));
    }

    @PostMapping("/scenario-runs/{id}/stop")
    ApiResponse<ScenarioRuns.Run> stop(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ok(runs.requestEnd(campus(jwt), id, io.capyiot.security.LiveUserJwtDecoder.legacyLoginName(jwt), ScenarioRuns.EndMode.STOP));
    }

    @PostMapping("/scenario-runs/{id}/recover")
    ApiResponse<ScenarioRuns.Run> recover(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ok(runs.requestEnd(campus(jwt), id, io.capyiot.security.LiveUserJwtDecoder.legacyLoginName(jwt), ScenarioRuns.EndMode.RECOVER));
    }
}
