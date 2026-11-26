package io.capyiot.device.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Append in the owning aggregate transaction; delivery and audit projection are retryable. */
public final class LocalEvents {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String context;
    public LocalEvents(JdbcTemplate db,ObjectMapper json,String context) { this.db=db; this.json=json; this.context=context; }
    public void append(String type,String id,String park,AuthorizationContext actor,Map<String,?> body) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Domain event requires local transaction");
        String eventId=UUID.randomUUID().toString(); Map<String,Object> event=new LinkedHashMap<>();
        event.put("eventId",eventId); event.put("eventType",type); event.put("schemaVersion",1);event.put("sourceContext",context); event.put("producer","device-service");event.put("aggregateType",aggregate(type));event.put("aggregateId",id);
        event.put("aggregateVersion",body.get("aggregateVersion") instanceof Number?body.get("aggregateVersion"):body.get("version") instanceof Number?body.get("version"):body.get("stateVersion") instanceof Number?body.get("stateVersion"):version(type,id));
        event.put("actorType",actor==null?"SERVICE":"USER");event.put("actorId",actor==null?context:actor.userId());event.put("source",context);
        event.put("deviceIds",deviceIds(type,id,body));
        event.put("areaIds",areaIds(type,id));
        event.put("parkId",park); event.put("occurredAt",Instant.now()); event.put("body",body);
        event.put("traceId",UUID.randomUUID().toString());
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet){
            var request=servlet.getRequest();String requestId=request.getHeader("X-Request-Id"),correlation=request.getHeader("X-Correlation-Id");
            event.put("requestId",requestId==null?request.getRequestId():requestId);event.put("correlationId",correlation==null?request.getRequestId():correlation);
        }
        if(actor!=null) event.put("actor",Map.of("userId",actor.userId(),"displayName",actor.displayName(),"sessionId",actor.sessionId(),"authorizationVersion",actor.authorizationVersion()));
        else event.put("actor",Map.of("serviceId",context));
        try {
            db.update("INSERT INTO outbox_event(campus_id,aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json,trace_parent) VALUES(?,?,?,?,?,?,?,?,?)",
                    park,context,id,type,eventId,"capyiot.domain."+context+".v1",id,json.writeValueAsString(event),io.capyiot.contract.Telemetry.traceParent());
            if(Set.of("DeviceConnectionLost","DeviceConnectionRestored","DeviceObservationAccepted").contains(type)){
                boolean connection=!type.equals("DeviceObservationAccepted");String stream=connection?"CONNECTIVITY":Objects.toString(body.get("streamKey"));
                long epoch=connection?0:((Number)body.get("sessionEpoch")).longValue();long sequence=((Number)body.get(connection?"stateVersion":"sequence")).longValue();
                long outboxId=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
                db.update("INSERT INTO device_fault_position(device_id,stream_key,session_epoch,sequence_no,event_id,source_outbox_id) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE session_epoch=VALUES(session_epoch),sequence_no=VALUES(sequence_no),event_id=VALUES(event_id),source_outbox_id=VALUES(source_outbox_id)",id,stream,epoch,sequence,eventId,outboxId);
            }
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private long version(String type,String id) {
        String query=null;
        if(context.equals("device")) {
            if(type.startsWith("Device")) query="SELECT version FROM device WHERE id=?";
            else if(type.startsWith("Area")) query="SELECT version FROM area WHERE id=?";
            else if(type.equals("MonitoringPointBindingEnded")) query="SELECT version FROM monitoring_point_binding WHERE binding_id=?";
            else if(type.startsWith("MonitoringPoint")) query="SELECT version FROM monitoring_point WHERE monitoring_point_id=?";
            else if(type.startsWith("Network")||type.startsWith("NETWORK"))query="SELECT version FROM network_dependency WHERE dependency_id=?";
            else if(type.startsWith("Power")||type.startsWith("POWER"))query="SELECT version FROM power_dependency WHERE power_dependency_id=?";
            else if(type.startsWith("Video")||type.startsWith("VIDEO"))query="SELECT version FROM video_channel_association WHERE association_id=?";
        }
        if(query==null)return 0;
        var rows=db.queryForList(query,Long.class,id);return rows.isEmpty()?0:rows.getFirst();
    }
    private String aggregate(String type){
        if(type.startsWith("Area"))return "Area";
        if(type.startsWith("MonitoringPointBinding"))return "MonitoringPointBinding";
        if(type.startsWith("MonitoringPoint"))return "MonitoringPoint";
        if(type.startsWith("Network")||type.startsWith("NETWORK"))return "NetworkDependency";
        if(type.startsWith("Power")||type.startsWith("POWER"))return "PowerDependency";
        if(type.startsWith("Video")||type.startsWith("VIDEO"))return "VideoChannelAssociation";
        return type.startsWith("DeviceConnection")||type.startsWith("DeviceRuntime")||type.equals("DeviceObservationAccepted")?"DeviceRuntimeState":"Device";
    }
    private List<String> deviceIds(String type,String id,Map<String,?> body) {
        Set<String> ids=new TreeSet<>();
        if(type.startsWith("Device"))ids.add(id);
        if(type.equals("MonitoringPointBindingEnded"))ids.addAll(db.queryForList("SELECT ipc_device_id FROM monitoring_point_binding WHERE binding_id=?",String.class,id));
        if(type.equals("NETWORKRelationEnded"))ids.addAll(db.queryForList("SELECT upstream_switch_id FROM network_dependency WHERE dependency_id=? UNION SELECT downstream_device_id FROM network_dependency WHERE dependency_id=?",String.class,id,id));
        if(type.equals("POWERRelationEnded"))ids.addAll(db.queryForList("SELECT poe_switch_id FROM power_dependency WHERE power_dependency_id=? UNION SELECT ipc_device_id FROM power_dependency WHERE power_dependency_id=?",String.class,id,id));
        if(type.equals("VIDEORelationEnded"))ids.addAll(db.queryForList("SELECT nvr_device_id FROM video_channel_association WHERE association_id=? UNION SELECT ipc_device_id FROM video_channel_association WHERE association_id=?",String.class,id,id));
        for(String field:List.of("deviceId","ipcDeviceId","poeSwitchId","nvrDeviceId","upstreamSwitchId","downstreamDeviceId")) {
            Object value=body.get(field);if(value instanceof String s&&!s.isBlank())ids.add(s);
        }
        return List.copyOf(ids);
    }
    private List<String> areaIds(String type,String id) {
        if(!context.equals("device"))return List.of();
        if(type.startsWith("Area"))return List.of(id);
        if(type.startsWith("MonitoringPoint"))return db.queryForList("SELECT area_id FROM monitoring_point WHERE monitoring_point_id=?",String.class,id);
        return List.of();
    }
}
