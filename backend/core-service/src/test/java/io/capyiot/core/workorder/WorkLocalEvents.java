package io.capyiot.core.workorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.core.platform.AuthorizationContext;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Append in the owning aggregate transaction; delivery and audit projection are retryable. */
public final class WorkLocalEvents {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String context;
    public WorkLocalEvents(JdbcTemplate db,ObjectMapper json,String context) { this.db=db; this.json=json; this.context=context; }
    public void append(String type,String id,String park,AuthorizationContext actor,Map<String,?> body) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Domain event requires local transaction");
        String eventId=UUID.randomUUID().toString(); Map<String,Object> event=new LinkedHashMap<>();
        event.put("eventId",eventId); event.put("eventType",type); event.put("schemaVersion",1);event.put("sourceContext",context); event.put("producer","core-service");event.put("aggregateType",aggregate(type));event.put("aggregateId",id);
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
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private long version(String type,String id){return 0;}
    private String aggregate(String type){if(!context.equals("workorder"))throw new IllegalArgumentException("Unknown domain");return "WorkOrder";}
    private List<String> deviceIds(String type,String id,Map<String,?> body){return db.queryForList("SELECT device_id FROM work_order_device_item WHERE work_order_id=? ORDER BY device_id",String.class,id);}
    private List<String> areaIds(String type,String id){return List.of();}
}
