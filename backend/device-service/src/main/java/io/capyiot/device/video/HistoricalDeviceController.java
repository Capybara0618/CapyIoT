package io.capyiot.device.video;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Historical telemetry is queried here only. No old consumer, state writer, or demo runner is shipped. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
class HistoricalDeviceController {
    private final AssetStore store;private final AssetService assets;private final DeviceIdentity users;
    HistoricalDeviceController(AssetStore store,AssetService assets,DeviceIdentity users){this.store=store;this.assets=assets;this.users=users;}
    private Set<String> scope(AuthorizationContext actor){var ids=new HashSet<>(assets.visibleHistoricalDeviceIds(actor,"event.read"));ids.retainAll(assets.visibleHistoricalDeviceIds(actor,"device.read"));return ids;}
    @GetMapping("/api/v1/legacy/events") ApiResponse<?> events(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String deviceId,@RequestParam(required=false) String eventType,@RequestParam(required=false) String status,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,HttpServletRequest req){
        return ApiResponse.ok(list(users.current(jwt),deviceId,eventType,status,from,to,page,size),req.getRequestId());
    }
    Map<String,Object> list(AuthorizationContext actor,String deviceId,String type,String status,Instant from,Instant to,int page,int size){
        if(page<1||page>100000||size<1||size>100||from!=null&&to!=null&&!from.isBefore(to))throw ApiProblem.invalid("历史事件分页或时间范围无效");
        if(type!=null&&!Set.of("HEARTBEAT","DEVICE_FAULT","DEVICE_RECOVERED","SENSOR_READING").contains(type))throw ApiProblem.invalid("历史事件类型无效");
        if(status!=null&&!Set.of("PROCESSED","IGNORED_STALE","IGNORED_SESSION","IGNORED_DISABLED").contains(status))throw ApiProblem.invalid("历史事件处理状态无效");
        var ids=scope(actor);if(deviceId!=null){ids.retainAll(Set.of(deviceId));}if(ids.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        var args=new HashMap<String,Object>();args.put("ids",ids);var where=new StringBuilder(" FROM device_event e JOIN device d ON d.id=e.device_id WHERE e.schema_version IS NULL AND e.device_id IN (:ids)");
        if(type!=null){where.append(" AND e.event_type=:type");args.put("type",type);}if(status!=null){where.append(" AND e.processing_status=:status");args.put("status",status);}
        if(from!=null){where.append(" AND e.received_at>=:from");args.put("from",DbTime.sql(from));}if(to!=null){where.append(" AND e.received_at<:to");args.put("to",DbTime.sql(to));}
        var db=new NamedParameterJdbcTemplate(store.db);long count=db.queryForObject("SELECT COUNT(*)"+where,args,Long.class);args.put("limit",size);args.put("offset",(page-1L)*size);
        var rows=db.queryForList("SELECT e.id,e.event_id,e.device_id,d.device_code,d.name AS device_name,e.event_type,e.source,e.processing_status,e.occurred_at,e.received_at,e.trace_id,e.session_epoch,e.seq"+where+" ORDER BY e.id DESC LIMIT :limit OFFSET :offset",args).stream().map(Views::row).toList();
        return Map.of("items",rows,"total",count,"page",page,"size",size);
    }
    @GetMapping("/api/v1/legacy/events/{id}") ApiResponse<?> event(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest req){
        var rows=store.db.queryForList("SELECT * FROM device_event WHERE event_id=? AND schema_version IS NULL",id);var ids=scope(users.current(jwt));rows=rows.stream().filter(r->ids.contains(r.get("device_id"))).toList();
        if(rows.size()!=1)throw ApiProblem.missing();var view=Views.row(rows.getFirst());view.remove("envelopeHash");view.remove("sessionToken");return ApiResponse.ok(view,req.getRequestId());
    }
    @GetMapping({"/api/v1/stream","/api/v1/devices/activity","/api/v1/devices/cursor","/api/v1/scenarios/templates","/api/v1/scenario-runs","/api/v1/scenario-runs/{id}"})
    void retired(){throw new ApiProblem(410,"LEGACY_FEATURE_RETIRED","旧演示与旧实时入口已退出，请使用当前设备资产、状态历史及虚拟设备接入功能");}
}
