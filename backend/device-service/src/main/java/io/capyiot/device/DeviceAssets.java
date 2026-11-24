package io.capyiot.device;

import static io.capyiot.device.DeviceModels.*;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceAssets {
    private final JdbcTemplate jdbc;
    private final DeviceMapper mapper;
    private final DeviceQueries queries;
    private final DeviceTopology topology;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    DeviceAssets(JdbcTemplate jdbc, DeviceMapper mapper, DeviceQueries queries, DeviceTopology topology,
            PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.queries = queries;
        this.topology = topology;
        this.passwords = passwords;
    }

    @Transactional
    public Registration create(String campusId, CreateDevice request) {
        lockCampus(campusId);
        validateLocation(campusId, request.buildingId(), request.floorId(), request.areaId());
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var asset = new DeviceAsset();
        asset.id = UUID.randomUUID().toString();
        asset.campusId = campusId;
        asset.deviceCode = request.deviceCode();
        asset.type = request.type().name();
        asset.name = request.name().trim();
        asset.enabled = true;
        asset.buildingId = request.buildingId();
        asset.floorId = request.floorId();
        asset.areaId = emptyToNull(request.areaId());
        asset.passwordHash = passwords.encode(credential);
        asset.sessionEpoch = 0L;
        asset.createdAt = Instant.now();
        asset.updatedAt = asset.createdAt;
        asset.version = 0L;
        mapper.insert(asset);
        jdbc.update("UPDATE device SET device_type=?,device_secret_hash=?,asset_status='IN_SERVICE',legacy_only=? WHERE id=?",
                asset.type.equals("CAMERA") ? "IPC" : asset.type.equals("SWITCH") ? "SWITCH" : null,
                asset.passwordHash, !Set.of("CAMERA","SWITCH").contains(asset.type), asset.id);
        jdbc.update("INSERT INTO device_state_snapshot(device_id) VALUES (?)", asset.id);
        setParent(campusId, asset.id, emptyToNull(request.upstreamNodeId()));
        topology.publishChange(campusId, asset.id);
        return new Registration(queries.find(campusId, asset.id), credential);
    }

    @Transactional
    public DeviceView update(String campusId, String id, UpdateDevice request) {
        lockCampus(campusId);
        queries.find(campusId, id);
        validateLocation(campusId, request.buildingId(), request.floorId(), request.areaId());
        int updated = jdbc.update("""
                UPDATE device SET name=?,enabled=?,building_id=?,floor_id=?,area_id=?,updated_at=?,version=version+1
                WHERE id=? AND campus_id=? AND version=?
                """, request.name().trim(), request.enabled(), request.buildingId(), request.floorId(),
                emptyToNull(request.areaId()), Timestamp.from(Instant.now()), id, campusId, request.expectedVersion());
        if (updated != 1) throw DeviceProblem.conflict("设备已被修改，请刷新后重试");
        setParent(campusId, id, emptyToNull(request.upstreamNodeId()));
        topology.publishChange(campusId, id);
        return queries.find(campusId, id);
    }

    Map<String, Object> locations(String campusId) {
        var campus = jdbc.queryForList("SELECT id,name FROM campus WHERE id=?", campusId);
        if (campus.isEmpty()) throw DeviceProblem.notFound("园区不存在");
        return Map.of("campus", campus.getFirst(),
                "buildings", jdbc.queryForList("SELECT id,name FROM building WHERE campus_id=? ORDER BY name,id", campusId),
                "floors", jdbc.queryForList("SELECT f.id,f.name,f.building_id buildingId FROM floor f JOIN building b ON b.id=f.building_id WHERE b.campus_id=? ORDER BY b.name,f.name,f.id", campusId),
                "areas", jdbc.queryForList("SELECT a.id,a.name,a.floor_id floorId FROM area a JOIN floor f ON f.id=a.floor_id JOIN building b ON b.id=f.building_id WHERE b.campus_id=? ORDER BY a.name,a.id", campusId));
    }

    @Transactional
    public Map<String, String> createLocation(String campusId, String kind, LocationRequest request) {
        lockCampus(campusId);
        String id = UUID.randomUUID().toString();
        switch (kind) {
            case "buildings" -> {
                if (!campusId.equals(request.parentId())) throw DeviceProblem.badRequest("园区归属无效");
                jdbc.update("INSERT INTO building(id,campus_id,name) VALUES (?,?,?)", id, campusId, request.name().trim());
                jdbc.update("INSERT INTO area(id,name,park_id,area_type,area_code) VALUES (?,?,?,'BUILDING',?)",id,request.name().trim(),campusId,id);
            }
            case "floors" -> {
                requireCount("SELECT COUNT(*) FROM building WHERE id=? AND campus_id=?", request.parentId(), campusId);
                jdbc.update("INSERT INTO floor(id,building_id,name) VALUES (?,?,?)", id, request.parentId(), request.name().trim());
                jdbc.update("INSERT INTO area(id,name,park_id,parent_area_id,area_type,area_code) VALUES (?,?,?,?,'FLOOR',?)",id,request.name().trim(),campusId,request.parentId(),id);
            }
            case "areas" -> {
                requireCount("SELECT COUNT(*) FROM floor f JOIN building b ON b.id=f.building_id WHERE f.id=? AND b.campus_id=?", request.parentId(), campusId);
                jdbc.update("INSERT INTO area(id,floor_id,name,park_id,parent_area_id,area_type,area_code) VALUES (?,?,?,?,?,'AREA',?)",
                        id, request.parentId(), request.name().trim(),campusId,request.parentId(),id);
            }
            default -> throw DeviceProblem.notFound("空间类型不存在");
        }
        return Map.of("id", id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> topology(String campusId, String buildingId) {
        var versions = jdbc.queryForList("SELECT topology_version FROM campus WHERE id=?", Long.class, campusId);
        if (versions.isEmpty()) throw DeviceProblem.notFound("园区不存在");
        var nodes = queries.list(campusId, null, buildingId, null, null, null, 1, 100);
        var edges = jdbc.queryForList("SELECT id,upstream_device_id source,downstream_device_id target,version FROM network_link WHERE campus_id=? ORDER BY id", campusId);
        var ids = new HashSet<>(nodes.items().stream().map(DeviceView::id).toList());
        edges.removeIf(edge -> !ids.contains(edge.get("source")) || !ids.contains(edge.get("target")));
        return Map.of("version", versions.getFirst(), "nodes", nodes.items(), "edges", edges,
                "totalNodes", nodes.total(), "truncated", nodes.total() > nodes.items().size());
    }

    @Transactional
    public Map<String, Long> link(String campusId, LinkRequest request) {
        long version = lockCampus(campusId);
        if (version != request.expectedVersion()) throw DeviceProblem.conflict("拓扑已更新，请重新获取快照");
        queries.find(campusId, request.downstreamDeviceId());
        setParent(campusId, request.downstreamDeviceId(), request.upstreamDeviceId());
        jdbc.update("UPDATE device SET version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                request.downstreamDeviceId());
        return Map.of("version", topology.publishChange(campusId, request.downstreamDeviceId()));
    }

    private long lockCampus(String campusId) {
        var rows = jdbc.queryForList("SELECT topology_version FROM campus WHERE id=? FOR UPDATE", Long.class, campusId);
        if (rows.isEmpty()) throw DeviceProblem.notFound("园区不存在");
        return rows.getFirst();
    }

    private void setParent(String campusId, String deviceId, String upstreamId) {
        if (upstreamId == null) {
            jdbc.update("DELETE FROM network_link WHERE downstream_device_id=? AND campus_id=?", deviceId, campusId);
            return;
        }
        DeviceView parent = queries.find(campusId, upstreamId);
        if (parent.type() != Type.SWITCH) throw DeviceProblem.badRequest("上游节点必须为交换机");
        var links = jdbc.queryForList("SELECT upstream_device_id,downstream_device_id FROM network_link WHERE campus_id=?", campusId);
        var parents = new HashMap<String, String>();
        for (var link : links) parents.put((String) link.get("downstream_device_id"), (String) link.get("upstream_device_id"));
        String current = upstreamId;
        var visited = new HashSet<String>();
        while (current != null) {
            if (current.equals(deviceId) || !visited.add(current)) throw DeviceProblem.badRequest("网络依赖不能形成环路");
            current = parents.get(current);
        }
        jdbc.update("""
                INSERT INTO network_link(id,campus_id,upstream_device_id,downstream_device_id) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE upstream_device_id=VALUES(upstream_device_id),version=version+1
                """, UUID.randomUUID().toString(), campusId, upstreamId, deviceId);
    }

    private void validateLocation(String campus, String building, String floor, String area) {
        requireCount("SELECT COUNT(*) FROM floor f JOIN building b ON b.id=f.building_id WHERE f.id=? AND b.id=? AND b.campus_id=?", floor, building, campus);
        if (area != null && !area.isBlank()) requireCount("SELECT COUNT(*) FROM area WHERE id=? AND floor_id=?", area, floor);
    }

    private void requireCount(String sql, Object... args) {
        if (!Objects.equals(jdbc.queryForObject(sql, Long.class, args), 1L)) throw DeviceProblem.badRequest("空间归属不正确");
    }

    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
