package io.capyiot.device.video;

import io.capyiot.contract.ApiResponse;
import io.capyiot.contract.internal.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class DeviceInternalController {
    private final AssetService assets;private final AssetStore store;private final DeviceSessions sessions;private final DeviceIdentity users;
    DeviceInternalController(AssetService assets,AssetStore store,DeviceSessions sessions,DeviceIdentity users){this.assets=assets;this.store=store;this.sessions=sessions;this.users=users;}
    private <T> ApiResponse<T> ok(T value,HttpServletRequest request){return ApiResponse.ok(value,request.getRequestId());}
    DeviceApi.Resource reference(String id){
        var value=assets.device(id);var row=store.db.queryForMap("SELECT version FROM device WHERE id=?",id);var ancestors=new ArrayList<String>();String area=value.areaId();
        while(area!=null&&ancestors.size()<100&&!ancestors.contains(area)){ancestors.add(area);var parents=store.db.queryForList("SELECT parent_area_id FROM area WHERE id=?",String.class,area);area=parents.isEmpty()?null:parents.getFirst();}
        return new DeviceApi.Resource(id,value.parkId(),value.areaId(),value.assetCode(),value.deviceType(),value.name(),value.assetStatus(),ancestors,((Number)row.get("version")).longValue());
    }
    @PostMapping("/internal/v1/resources/validate") ApiResponse<?> resources(@RequestBody DeviceApi.ResourceValidation body,HttpServletRequest req){return ok(new DeviceApi.ResourceValidity(body.parkId(),assets.parkExists(body.parkId()),assets.areasBelongToPark(body.parkId(),body.areaIds()),0,Instant.now()),req);}
    @PostMapping("/internal/v1/resources/audit-scope") ApiResponse<?> audit(@AuthenticationPrincipal Jwt jwt,@RequestBody DeviceApi.AuditScopeRequest body,HttpServletRequest req){return ok(new DeviceApi.AuditScopeResult(assets.auditVisible(users.current(jwt),body.parkId(),body.deviceIds(),body.areaIds()),Instant.now()),req);}
    @PostMapping("/internal/v1/devices/references") ApiResponse<?> references(@RequestBody DeviceApi.Batch body,HttpServletRequest req){return ok(references(body),req);}
    DeviceApi.ScopeResult references(DeviceApi.Batch body){return new DeviceApi.ScopeResult(body.deviceIds().stream().map(this::reference).toList(),Instant.now());}
    @PostMapping("/internal/v1/devices/scope-check") ApiResponse<?> scope(@AuthenticationPrincipal Jwt jwt,@RequestBody DeviceApi.ScopeRequest body,HttpServletRequest req){return ok(scope(users.current(jwt),body),req);}
    DeviceApi.ScopeResult scope(AuthorizationContext actor,DeviceApi.ScopeRequest body){if(actor.sessionId().startsWith("delegation:")&&!body.permission().equals("workorder.create"))throw ApiProblem.forbidden();assets.requireVisibleDevices(actor,body.permission(),body.deviceIds());return references(new DeviceApi.Batch(body.deviceIds()));}
    @PostMapping("/internal/v1/devices/visible-page") ApiResponse<?> visible(@AuthenticationPrincipal Jwt jwt,@RequestBody DeviceApi.VisiblePageRequest body,HttpServletRequest req){return ok(visible(users.current(jwt),body),req);}
    DeviceApi.VisiblePage visible(AuthorizationContext actor,DeviceApi.VisiblePageRequest body){var ids=assets.visibleDeviceIds(actor,body.permission()).stream().filter(id->body.afterDeviceId()==null||id.compareTo(body.afterDeviceId())>0).sorted().limit(101).toList();return new DeviceApi.VisiblePage(ids.stream().limit(100).toList(),ids.size()>100?ids.get(99):null,Instant.now());}
    @PostMapping("/internal/v1/topology/network-history") ApiResponse<?> network(@RequestBody DeviceApi.NetworkPageRequest body,HttpServletRequest req){return ok(network(body),req);}
    @PostMapping("/internal/v1/topology/snapshot") ApiResponse<?> topology(@RequestBody DeviceApi.TopologyRequest body,HttpServletRequest req){
        var selected=new LinkedHashSet<>(body.deviceIds());for(String id:selected)if(!assets.device(id).parkId().equals(body.parkId()))throw ApiProblem.invalid("拓扑设备不在同一园区");
        var relations=new ArrayList<DeviceApi.Relation>();boolean complete=true;long version=0;
        for(String[] type:List.of(new String[]{"network_dependency","dependency_id","upstream_switch_id","downstream_device_id","NETWORK","upstream_port_ref"},
                new String[]{"power_dependency","power_dependency_id","poe_switch_id","ipc_device_id","POWER","poe_port"},
                new String[]{"video_channel_association","association_id","nvr_device_id","ipc_device_id","VIDEO","channel_no"})){
            String placeholders=String.join(",",Collections.nCopies(body.deviceIds().size(),"?"));var arguments=new ArrayList<Object>();arguments.add(body.parkId());arguments.add(DbTime.sql(body.asOf()));arguments.add(DbTime.sql(body.asOf()));arguments.addAll(body.deviceIds());arguments.addAll(body.deviceIds());
            var rows=store.db.queryForList("SELECT * FROM "+type[0]+" WHERE park_id=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) AND ("+type[2]+" IN ("+placeholders+") OR "+type[3]+" IN ("+placeholders+")) ORDER BY "+type[1]+" LIMIT 1001",arguments.toArray());
            for(var r:rows){String from=r.get(type[2]).toString(),to=r.get(type[3]).toString();
                int added=(selected.contains(from)?0:1)+(selected.contains(to)?0:1);
                if(relations.size()>=1000||selected.size()+added>100){complete=false;continue;}selected.add(from);selected.add(to);
                long rowVersion=((Number)r.get("version")).longValue();version=Math.max(version,rowVersion);
                relations.add(new DeviceApi.Relation(r.get(type[1]).toString(),type[4],from,to,type[4].equals("VIDEO")?null:(String)r.get(type[5]),type[4].equals("VIDEO")?((Number)r.get(type[5])).intValue():null,DbTime.instant(r.get("valid_from")),DbTime.instant(r.get("valid_to")),rowVersion));
            }
        }
        // Informational maximum of relation versions, never a global topology CAS token.
        return ok(new DeviceApi.TopologyResult(selected.stream().map(this::reference).toList(),relations,body.asOf(),version,complete),req);
    }
    DeviceApi.NetworkPage network(DeviceApi.NetworkPageRequest body){var rows=store.db.queryForList("SELECT * FROM network_dependency WHERE park_id=? AND dependency_id>? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) ORDER BY dependency_id LIMIT 101",body.parkId(),Objects.toString(body.afterRelationId(),""),DbTime.sql(body.asOf()),DbTime.sql(body.asOf()));
        var relations=rows.stream().limit(100).map(r->new DeviceApi.Relation(r.get("dependency_id").toString(),"NETWORK",r.get("upstream_switch_id").toString(),r.get("downstream_device_id").toString(),(String)r.get("upstream_port_ref"),null,DbTime.instant(r.get("valid_from")),DbTime.instant(r.get("valid_to")),((Number)r.get("version")).longValue())).toList();
        return new DeviceApi.NetworkPage(relations,rows.size()>100?relations.getLast().relationId():null,body.asOf());
    }
    @PostMapping("/internal/v1/devices/runtime-snapshot") ApiResponse<?> runtime(@RequestBody DeviceApi.Batch body,HttpServletRequest req){return ok(runtime(body),req);}
    DeviceApi.RuntimeResult runtime(DeviceApi.Batch body){return store.tx.execute(status->runtimeSnapshot(body));}
    private DeviceApi.RuntimeResult runtimeSnapshot(DeviceApi.Batch body){
        var result=new ArrayList<DeviceApi.RuntimeState>();boolean complete=true;
        for(String id:body.deviceIds()){assets.device(id);var states=store.db.queryForList("SELECT * FROM device_runtime_state WHERE device_id=?",id);
            var positions=store.db.queryForList("SELECT * FROM device_fault_position WHERE device_id=? ORDER BY stream_key LIMIT 1001",id);if(positions.size()>1000)complete=false;
            var required=positions.stream().limit(1000).map(p->new DeviceApi.ObservationPosition(p.get("event_id").toString(),p.get("stream_key").toString(),((Number)p.get("session_epoch")).longValue(),((Number)p.get("sequence_no")).longValue())).toList();
            if(states.isEmpty()){complete=false;result.add(new DeviceApi.RuntimeState(id,"UNKNOWN","UNKNOWN","NO_DATA",0,null,null,required));continue;}
            var s=states.getFirst();result.add(new DeviceApi.RuntimeState(id,s.get("connectivity_status").toString(),s.get("operating_mode").toString(),s.get("data_freshness").toString(),((Number)s.get("version")).longValue(),DbTime.instant(s.get("last_heartbeat_received_at")),DbTime.instant(s.get("last_business_data_received_at")),required));
        }return new DeviceApi.RuntimeResult(result,Instant.now(),complete);
    }
    @PostMapping("/internal/v1/events/evidence") ApiResponse<?> evidence(@AuthenticationPrincipal Jwt jwt,@RequestBody FaultApi.EvidenceRequest body,HttpServletRequest req){return ok(evidence(users.current(jwt),body),req);}
    FaultApi.EvidenceResult evidence(AuthorizationContext actor,FaultApi.EvidenceRequest body){boolean visible=assets.visibleEvidence(actor,body.evidenceReferenceId(),body.workOrderDeviceIds());return new FaultApi.EvidenceResult(visible,visible,visible?body.evidenceReferenceId():null,Instant.now());}
    @GetMapping("/internal/v1/devices/{id}/sessions/{sid}") ApiResponse<?> session(@PathVariable String id,@PathVariable String sid,HttpServletRequest req){return ok(sessions.current(id,sid),req);}
    @PostMapping("/internal/v1/ingestion/token") ApiResponse<?> ingestion(@RequestBody DeviceApi.IngestionIdentity body,HttpServletRequest req){return ok(new DeviceApi.IngestionCredential(sessions.ingestionToken(body.clientId()),Instant.now().plusSeconds(900)),req);}
}
