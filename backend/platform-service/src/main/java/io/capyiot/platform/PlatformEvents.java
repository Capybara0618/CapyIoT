package io.capyiot.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class PlatformEvents {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public PlatformEvents(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    void append(long auditId,AuthorizationContext actor,String action,String resource,String id,Long before,Long after) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Platform fact requires local transaction");
        var event=new LinkedHashMap<String,Object>();String eventId=UUID.randomUUID().toString();
        event.put("schemaVersion",1);event.put("eventId",eventId);event.put("eventType",action);
        event.put("sourceContext","platform");event.put("producer","platform-service");event.put("source","platform");
        // Each audit record is an immutable fact. Do not invent a user/grant version for login/logout facts.
        String factId=Long.toString(auditId);
        event.put("aggregateType","AUDIT_FACT");event.put("aggregateId",factId);event.put("aggregateVersion",0);
        event.put("occurredAt",Instant.now());event.put("actorType",actor==null?"SERVICE":"USER");
        event.put("actorId",actor==null?"platform-service":actor.userId());
        event.put("actor",actor==null?Map.of("serviceId","platform-service"):Map.of("userId",actor.userId()));
        var body=new LinkedHashMap<String,Object>();body.put("auditId",factId);body.put("action",action);body.put("resourceType",resource);body.put("resourceId",id);
        if(before!=null)body.put("beforeVersion",before);if(after!=null)body.put("afterVersion",after);event.put("body",body);
        try { db.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json) VALUES(?,?,?,?,?,?,?)",
                "AUDIT_FACT",factId,action,eventId,"capyiot.domain.platform.v1",factId,json.writeValueAsString(event)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Invalid platform fact");}
    }
}
