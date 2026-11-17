package io.capyiot.device;

import io.capyiot.contract.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class EventController {
    private final DeviceEvents events;
    private final DeviceQueries devices;
    private final DeviceNotifications notifications;

    EventController(DeviceEvents events, DeviceQueries devices, DeviceNotifications notifications) {
        this.events = events; this.devices = devices; this.notifications = notifications;
    }

    @GetMapping("/events")
    ApiResponse<DeviceEvents.EventPage> events(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String eventType, @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String scenarioRunId) {
        return DeviceController.ok(events.list(DeviceController.campus(jwt), deviceId, eventType, status, from, to, page, size, cursor, scenarioRunId));
    }

    @GetMapping("/events/{eventId}")
    ApiResponse<DeviceEvents.EventView> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String eventId) {
        return DeviceController.ok(events.find(DeviceController.campus(jwt), eventId));
    }

    @GetMapping("/devices/{id}/events")
    ApiResponse<DeviceEvents.EventPage> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        String campus = DeviceController.campus(jwt);
        devices.find(campus, id);
        return DeviceController.ok(events.list(campus, id, null, null, null, null, page, size, null, null));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) throws IOException {
        return notifications.subscribe(DeviceController.campus(jwt), jwt.getExpiresAt());
    }
}
