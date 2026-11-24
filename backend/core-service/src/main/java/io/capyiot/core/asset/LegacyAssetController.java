package io.capyiot.core.asset;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.capyiot.contract.ApiResponse;
import io.capyiot.core.*;
import io.capyiot.core.platform.*;
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

/** Old UI shapes adapt to the same asset commands, scope checks, histories and local transaction. */
@RestController
@RequestMapping(value="/api/v1",headers="X-CapyIoT-Legacy-View=true")
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class LegacyAssetController {
    private final AssetService assets;private final AssetStore store;private final PlatformService platform;private final ObjectMapper json;private final CommandJournal commands;
    LegacyAssetController(AssetService assets,AssetStore store,PlatformService platform,ObjectMapper json,@Value("${capyiot.foundation.service-key}") String key){this.assets=assets;this.store=store;this.platform=platform;this.json=json;commands=new CommandJournal(store.db,store.tx,json,key);}
    private AuthorizationContext actor(Jwt jwt,String permission){var actor=platform.current(jwt);if(actor.require(permission).stream().noneMatch(g->g.scopeType().equals("PARK")&&"CAMPUS-A".equals(g.parkId())))throw ApiProblem.forbidden();return actor;}
    private <T> ApiResponse<T> ok(T body,HttpServletRequest req){return ApiResponse.ok(body,req.getRequestId());}
    record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String deviceCode,@NotBlank String type,@NotBlank @Size(max=100) String name,
                  @NotBlank String buildingId,@NotBlank String floorId,String areaId,String upstreamNodeId){}
    record Update(@NotBlank @Size(max=100) String name,@NotNull Boolean enabled,@NotBlank String buildingId,@NotBlank String floorId,String areaId,String upstreamNodeId,@NotNull @Min(0) Long expectedVersion){}
    private AssetModels.DeviceType type(String legacy){return switch(legacy){case "CAMERA","IPC"->AssetModels.DeviceType.IPC;case "NVR"->AssetModels.DeviceType.NVR;case "SWITCH"->AssetModels.DeviceType.SWITCH;default->throw ApiProblem.invalid("新纳管设备仅支持 IPC、NVR、SWITCH；其他类型只保留历史");};}
    private String subkey(String key,String action){return "legacy-"+action+"-"+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));}
    private String location(String building,String floor,String area){
        var floors=store.db.queryForList("SELECT id FROM area WHERE id=? AND parent_area_id=? AND area_type='FLOOR' AND park_id='CAMPUS-A'",String.class,floor,building);
        if(floors.isEmpty())throw ApiProblem.invalid("楼宇与楼层归属不一致");
        if(area==null||area.isBlank())return floor;
        if(!Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM area WHERE id=? AND parent_area_id=? AND park_id='CAMPUS-A'",Boolean.class,area,floor)))throw ApiProblem.invalid("区域归属不一致");return area;
    }
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
        var actor=actor(jwt,"device.read");var ids=assets.visibleDeviceIds(actor,"device.read");long online=0,offline=0,abnormal=0;
        for(String id:ids){var d=assets.detail(actor,id);if(d.get("connectivityStatus").equals("ONLINE"))online++;if(d.get("connectivityStatus").equals("OFFLINE"))offline++;if(Set.of("WARNING","FAULT").contains(d.get("healthStatus")))abnormal++;}
        return ok(Map.of("total",ids.size(),"online",online,"offline",offline,"abnormal",abnormal),req);
    }
    @GetMapping("/devices/{id}") ApiResponse<?> device(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req){return ok(view(actor(jwt,"device.read"),id),req);}
    @PostMapping("/devices") ApiResponse<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create c,HttpServletRequest req){
        var actor=actor(jwt,"device.manage");String area=location(c.buildingId(),c.floorId(),c.areaId());var type=type(c.type());
        var result=commands.execute("CAMPUS-A",key,actor.userId(),"LegacyDeviceRegistered",c,()->{
            var registration=assets.createDevice(actor,subkey(key,"create"),new AssetModels.CreateDevice(c.deviceCode(),type,c.name(),area,null,null,null,null,null,Map.of()));String id=registration.path("device").path("deviceId").asText();
            assets.updateDevice(actor,subkey(key,"commission"),id,new AssetModels.UpdateDevice(null,null,null,null,null,null,null,null,AssetModels.AssetStatus.IN_SERVICE,0L));
            setParent(actor,key,id,c.upstreamNodeId());
            Map<String,Object> response=new LinkedHashMap<>();response.put("device",view(actor,id));if(registration.has("deviceSecret"))response.put("deviceSecret",registration.path("deviceSecret").asText());return response;
        });return ok(result,req);
    }
    @PatchMapping("/devices/{id}") ApiResponse<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Update c,HttpServletRequest req){
        var actor=actor(jwt,"device.manage");String area=location(c.buildingId(),c.floorId(),c.areaId());
        var result=commands.execute("CAMPUS-A",key,actor.userId(),"LegacyDeviceUpdated",Map.of("id",id,"body",c),()->{
            assets.updateDevice(actor,subkey(key,"update"),id,new AssetModels.UpdateDevice(c.name(),area,null,null,null,null,null,null,c.enabled()?AssetModels.AssetStatus.IN_SERVICE:AssetModels.AssetStatus.OUT_OF_SERVICE,c.expectedVersion()));
            setParent(actor,key,id,c.upstreamNodeId());return view(actor,id);
        });return ok(result,req);
    }
    private void setParent(AuthorizationContext actor,String key,String id,String parent){
        var rows=store.db.queryForList("SELECT dependency_id,upstream_switch_id,version FROM network_dependency WHERE downstream_device_id=? AND valid_to IS NULL",id);
        String desired=blank(parent);if(!rows.isEmpty()&&Objects.equals(rows.getFirst().get("upstream_switch_id"),desired))return;
        if(!rows.isEmpty()){var edge=rows.getFirst();assets.end(actor,subkey(key,"end-parent"),"network-dependencies",edge.get("dependency_id").toString(),new AssetModels.EndRelation("更新设备网络归属",((Number)edge.get("version")).longValue()));}
        if(desired!=null)assets.network(actor,subkey(key,"parent"),new AssetModels.Network(desired,id,null,null,"兼容前端明确指定上游"));
    }
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
