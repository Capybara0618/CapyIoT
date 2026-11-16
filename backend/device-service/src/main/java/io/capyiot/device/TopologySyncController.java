package io.capyiot.device;

import io.capyiot.contract.TopologyEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TopologySyncController {
    private final DeviceTopology topology;
    private final byte[] key;

    TopologySyncController(DeviceTopology topology, @Value("${capyiot.topology-sync-token:}") String token) {
        this.topology = topology;
        this.key = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/topology")
    TopologyEvent.Page snapshot(@RequestHeader(name = "X-Topology-Token", defaultValue = "") String token,
            @RequestParam String campusId, @RequestParam(defaultValue = "") String after,
            @RequestParam(required = false) Long version) {
        if (key.length < 32 || !MessageDigest.isEqual(key, token.getBytes(StandardCharsets.UTF_8))) {
            throw new DeviceProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "拓扑同步凭据无效");
        }
        return topology.snapshot(campusId, after, version);
    }
}
