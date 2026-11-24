package io.capyiot.core.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The platform owns the audit projection; it never joins another context's tables. */
@Component
@ConditionalOnProperty(name="capyiot.domain-runtime.enabled",havingValue="true")
class DomainAuditProjection {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    DomainAuditProjection(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json){this.db=db;this.tx=tx;this.json=json;}

    @KafkaListener(topics={"capyiot.domain.device.v1","capyiot.domain.fault.v1","capyiot.domain.workorder.v1"},groupId="core-platform-audit-v1")
    void consume(ConsumerRecord<String,String> record)throws Exception {
        JsonNode event=json.readTree(record.value());
        String context=text(event,"sourceContext",32),eventId=text(event,"eventId",64),id=text(event,"aggregateId",64);
        if(!Set.of("device","fault","workorder").contains(context)||!record.topic().equals("capyiot.domain."+context+".v1")
                ||!Objects.equals(record.key(),id)||event.path("schemaVersion").asInt()!=1||!event.path("producer").asText().equals("core-service"))throw new IllegalArgumentException("Invalid domain audit envelope");
        UUID.fromString(eventId);
        String action=text(event,"eventType",64),park=text(event,"parkId",64),resource=text(event,"aggregateType",64);
        var actor=event.path("actor");String user=optional(actor,"userId",64),actorId=user==null?context:user;
        Map<String,Object> details=new LinkedHashMap<>();
        // Keep only authorization references and a small change summary. Never copy a raw event payload.
        List<String> deviceIds=new ArrayList<>();
        if(event.path("deviceIds").isArray())for(var device:event.path("deviceIds")) {
            if(!device.isTextual()||!device.asText().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid audit device reference");
            deviceIds.add(device.asText());
        }
        details.put("deviceIds",deviceIds.stream().distinct().sorted().toList());
        List<String> areaIds=new ArrayList<>();
        if(event.path("areaIds").isArray())for(var area:event.path("areaIds")) {
            if(!area.isTextual()||!area.asText().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid audit area reference");
            areaIds.add(area.asText());
        }
        details.put("areaIds",areaIds.stream().distinct().sorted().toList());
        for(String field:List.of("status","priority","rootCauseStatus","reason","deviceType","credentialVersion","stateVersion")) {
            JsonNode value=event.path("body").path(field);
            if(value.isTextual())details.put(field,value.asText().substring(0,Math.min(1000,value.asText().length())));
            else if(value.isNumber()||value.isBoolean())details.put(field,value);
        }
        String serialized=json.writeValueAsString(details);
        tx.executeWithoutResult(status->{
            if(db.update("INSERT IGNORE INTO audit_event_inbox(consumer_name,event_id,source_context) VALUES('domain-audit-v1',?,?)",eventId,context)==0)return;
            db.update("""
                INSERT INTO audit_record(actor_type,actor_id,actor_user_id,actor_name_snapshot,action_code,resource_type,resource_id,
                 park_id,result,source,reason,after_version,request_id,correlation_id,causation_id,session_id,authorization_version,details_json,source_event_id)
                VALUES(?,?,?,?,?,?,?,?,'SUCCESS',?,?,?,?,?,?,?,?,?,?)
                """,user==null?"SERVICE":"USER",actorId,user,optional(actor,"displayName",100),action,resource,id,park,context,
                    optional(event.path("body"),"reason",1000),event.path("aggregateVersion").asLong(),optional(event,"requestId",128),
                    optional(event,"correlationId",128),optional(event,"causationId",128),optional(actor,"sessionId",64),
                    actor.has("authorizationVersion")?actor.path("authorizationVersion").asLong():null,serialized,eventId);
        });
    }
    private static String text(JsonNode node,String name,int max){String s=optional(node,name,max);if(s==null)throw new IllegalArgumentException("Missing domain audit field");return s;}
    private static String optional(JsonNode node,String name,int max){if(!node.hasNonNull(name))return null;var v=node.path(name);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>max)throw new IllegalArgumentException("Invalid domain audit field");return v.asText();}
}
