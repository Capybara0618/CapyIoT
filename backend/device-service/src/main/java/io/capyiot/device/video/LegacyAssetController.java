package io.capyiot.device.video;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Read-only compatibility shapes over the authoritative device model and current scope. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
@RequestMapping(value="/api/v1",headers="X-CapyIoT-Legacy-View=true")
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class LegacyAssetController {
    private final AssetService assets;private final AssetStore store;private final DeviceIdentity platform;private final ObjectMapper json;
    LegacyAssetController(AssetService assets,AssetStore store,DeviceIdentity platform,ObjectMapper json){this.assets=assets;this.store=store;this.platform=platform;this.json=json;}
    private AuthorizationContext actor(Jwt jwt,String permission){var actor=platform.current(jwt);if(actor.require(permission).stream().noneMatch(g->g.scopeType().equals("PARK")&&"CAMPUS-A".equals(g.parkId())))throw ApiProblem.forbidden();return actor;}
    private <T> ApiResponse<T> ok(T body,HttpServletRequest req){return ApiResponse.ok(body,req.getRequestId());}
    private AssetModels.DeviceType type(String legacy){return switch(legacy){case "CAMERA","IPC"->AssetModels.DeviceType.IPC;case "NVR"->AssetModels.DeviceType.NVR;case "SWITCH"->AssetModels.DeviceType.SWITCH;default->throw ApiProblem.invalid("新纳管设备仅支持 IPC、NVR、SWITCH；其他类型只保留历史");};}
    @GetMapping("/locations") ApiResponse<?> locations(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){
        actor(jwt,"device.read");var nodes=store.db.queryForList("SELECT * FROM area WHERE park_id='CAMPUS-A' AND status='ACTIVE'");
        return ok(Map.of("campus",Map.of("id","CAMPUS-A","name",store.db.queryForObject("SELECT name FROM park WHERE park_id='CAMPUS-A'",String.class)),
                "buildings",nodes.stream().filter(a->a.get("area_type").equals("BUILDING")).map(a->Map.of("id",a.get("id"),"name",a.get("name"))).toList(),
                "floors",nodes.stream().filter(a->a.get("area_type").equals("FLOOR")&&a.get("parent_area_id")!=null).map(a->Map.of("id",a.get("id"),"name",a.get("name"),"buildingId",a.get("parent_area_id"))).toList(),
                "areas",nodes.stream().filter(a->a.get("area_type").equals("AREA")&&a.get("parent_area_id")!=null).map(a->Map.of("id",a.get("id"),"name",a.get("name"),"floorId",a.get("parent_area_id"))).toList()),req);
    }
    @GetMapping("/devices") ApiResponse<?> devices(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String type,@RequestParam(required=false) String buildingId,
        @RequestParam(required=false) String connectivity,@RequestParam(required=false) String health,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){
        var actor=actor(jwt,"device.read");String targetType=type==null||type.isBlank()?null:type(type).name();
        var visible=assets.visibleDeviceIds(actor,"device.read");
        var items=store.db.queryForList("SELECT id FROM device ORDER BY created_at DESC,id DESC",String.class).stream().filter(visible::contains).map(id->view(actor,id))
                .filter(d->targetType==null||targetType.equals(d.get("deviceType"))).filter(d->blank(buildingId)==null||buildingId.equals(d.get("buildingId")))
                .filter(d->blank(connectivity)==null||connectivity.equals(d.get("connectivityStatus"))).filter(d->blank(health)==null||health.equals(d.get("healthStatus")))
                .filter(d->blank(keyword)==null||(d.get("name")+" "+d.get("deviceCode")).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))).toList();
        return ok(Views.page(items,page,size),req);
    }
    @GetMapping("/devices/summary") ApiResponse<?> summary(@AuthenticationPrincipal Jwt jwt,HttpServletRequest req){
        return ok(assets.overview(actor(jwt,"device.read")),req);
    }
    @GetMapping("/devices/{id}") ApiResponse<?> device(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req){return ok(view(actor(jwt,"device.read"),id),req);}


    private Map<String,Object> view(AuthorizationContext actor,String id){
        var d=assets.detail(actor,id);var raw=store.db.queryForMap("SELECT * FROM device WHERE id=?",id);Map<String,Object> result=new LinkedHashMap<>(d);
        result.put("id",id);result.put("deviceCode",d.get("assetCode"));result.put("type",d.get("deviceType").equals("IPC")?"CAMERA":d.get("deviceType"));result.put("enabled",!Set.of("OUT_OF_SERVICE","RETIRED").contains(d.get("assetStatus")));result.put("campusId",d.get("parkId"));
        result.put("lastHeartbeatAt",d.get("lastHeartbeatReceivedAt"));result.put("createdAt",DbTime.instant(raw.get("created_at")));result.put("sessionEpoch",raw.get("session_epoch"));result.put("seq",0);
        result.put("healthStatus",switch(d.get("healthStatus").toString()){case "HEALTHY"->"NORMAL";case "WARNING","FAULT"->"ABNORMAL";default->"UNKNOWN";});
        String next=d.get("areaId").toString();Set<String> seen=new HashSet<>();result.put("buildingId",null);result.put("buildingName",null);result.put("floorId",null);result.put("floorName",null);result.put("areaId",null);result.put("areaName",null);
        while(next!=null&&seen.add(next)){var a=store.db.queryForMap("SELECT * FROM area WHERE id=?",next);String prefix=switch(a.get("area_type").toString()){case "BUILDING"->"building";case "FLOOR"->"floor";default->"area";};if(result.get(prefix+"Id")==null){result.put(prefix+"Id",a.get("id"));result.put(prefix+"Name",a.get("name"));}next=(String)a.get("parent_area_id");}
        var parents=store.db.queryForList("SELECT n.upstream_switch_id,d.name FROM network_dependency n JOIN device d ON d.id=n.upstream_switch_id WHERE n.downstream_device_id=? AND n.valid_to IS NULL",id);
        result.put("upstreamNodeId",parents.isEmpty()?null:parents.getFirst().get("upstream_switch_id"));result.put("upstreamNodeName",parents.isEmpty()?null:parents.getFirst().get("name"));return Views.row(result);
    }
    private static String blank(String text){return text==null||text.isBlank()?null:text;}
}
