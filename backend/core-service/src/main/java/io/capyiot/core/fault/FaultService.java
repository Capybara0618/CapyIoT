package io.capyiot.core.fault;

import static io.capyiot.core.fault.FaultModels.*;
import com.fasterxml.jackson.databind.*;
import io.capyiot.core.*;
import io.capyiot.core.platform.*;
import io.capyiot.core.ports.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="capyiot.faults.enabled",havingValue="true")
public class FaultService implements HealthQueryPort,IncidentGatePort {
    private final FaultStore store;private final DeviceQueryPort devices;private final ObjectProvider<WorkOrderQueryPort> orders;
    private final ObjectMapper json;private final CommandJournal commands;private final LocalEvents events;
    private final long correlationSeconds,healthySeconds;
    public FaultService(FaultStore store,DeviceQueryPort devices,ObjectProvider<WorkOrderQueryPort> orders,ObjectMapper json,@Value("${capyiot.foundation.service-key}") String key,
            @Value("${capyiot.faults.correlation-window-seconds:120}") long correlationSeconds,@Value("${capyiot.faults.health-observation-seconds:120}") long healthySeconds){
        this.store=store;this.devices=devices;this.orders=orders;this.json=json;commands=new CommandJournal(store.db,store.tx,json,key);events=new LocalEvents(store.db,json,"fault");
        if(correlationSeconds<1||healthySeconds<1)throw new IllegalArgumentException("Fault observation windows must be positive");this.correlationSeconds=correlationSeconds;this.healthySeconds=healthySeconds;
    }
    private Map<String,Object> anomaly(String id){var rows=store.db.queryForList("SELECT * FROM anomaly WHERE anomaly_id=?",id);if(rows.isEmpty())throw ApiProblem.missing();return rows.getFirst();}
    private Map<String,Object> incident(String id){var rows=store.db.queryForList("SELECT * FROM incident WHERE incident_id=?",id);if(rows.isEmpty())throw ApiProblem.missing();return rows.getFirst();}
    private Set<String> incidentDevices(String id){return new HashSet<>(store.db.queryForList("SELECT DISTINCT a.device_id FROM anomaly a JOIN incident_anomaly_link l ON l.anomaly_id=a.anomaly_id WHERE l.incident_id=? AND a.device_id IS NOT NULL",String.class,id));}
    private void visible(AuthorizationContext actor,String permission,Map<String,Object> anomaly){
        if(anomaly.get("device_id")==null)throw ApiProblem.missing();devices.requireVisibleDevices(actor,permission,List.of(anomaly.get("device_id").toString()));
    }
    @Override public IncidentRef requireIncident(AuthorizationContext actor,String permission,String id){
        var i=incident(id);Set<String> ids=incidentDevices(id);if(ids.isEmpty())throw ApiProblem.missing();devices.requireVisibleDevices(actor,permission,ids);
        return new IncidentRef(id,i.get("park_id").toString(),i.get("status").toString(),i.get("priority").toString(),Views.version(i));
    }
    @Override public Map<String,String> deviceHealth(Set<String> ids){
        Map<String,String> result=new HashMap<>();for(String id:ids){
            var active=store.db.queryForList("SELECT severity FROM anomaly WHERE device_id=? AND status='ACTIVE'",String.class,id);
            boolean observed=Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM fault_device_observation WHERE device_id=? AND last_good_observed_at>TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3))",Boolean.class,id,-healthySeconds));
            result.put(id,active.contains("FAULT")?"FAULT":!active.isEmpty()?"WARNING":observed?"HEALTHY":"UNKNOWN");
        }return result;
    }
    public Map<String,Object> anomalies(AuthorizationContext actor,String deviceId,String type,String severity,String status,Instant from,Instant to,int page,int size){
        Set<String> visible=devices.visibleDeviceIds(actor,"anomaly.read");
        var rows=store.db.queryForList("SELECT * FROM anomaly ORDER BY last_observed_at DESC,anomaly_id").stream().filter(a->visible.contains(a.get("device_id")))
                .filter(a->deviceId==null||deviceId.equals(a.get("device_id"))).filter(a->type==null||type.equals(a.get("anomaly_type")))
                .filter(a->severity==null||severity.equals(a.get("severity"))).filter(a->status==null||status.equals(a.get("status")))
                .filter(a->from==null||!DbTime.instant(a.get("last_observed_at")).isBefore(from)).filter(a->to==null||DbTime.instant(a.get("last_observed_at")).isBefore(to)).map(Views::row).toList();
        return Views.page(rows,page,size);
    }
    public Map<String,Object> anomalyDetail(AuthorizationContext actor,String id){
        var raw=anomaly(id);visible(actor,"anomaly.read",raw);var result=Views.row(raw);result.remove("activeDedupeKey");
        result.put("evidenceReferences",store.db.queryForList("SELECT * FROM anomaly_evidence WHERE anomaly_id=? ORDER BY received_at,evidence_id",id).stream().map(Views::row).toList());
        result.put("legacyMappings",store.db.queryForList("SELECT legacy_alarm_id AS legacyAlarmId,mapping_reason AS mappingReason FROM legacy_alarm_mapping WHERE target_type='ANOMALY' AND target_id=?",id));return result;
    }
    public JsonNode dismiss(AuthorizationContext actor,String key,String id,Reason c){
        var a=anomaly(id);visible(actor,"anomaly.dismiss",a);String park=a.get("park_id").toString();
        var result=write(actor,key,park,"AnomalyDismissed",Map.of("id",id,"body",c),()->{
            var current=anomaly(id);Views.version(current,c.expectedVersion());if(!current.get("status").equals("ACTIVE"))throw ApiProblem.conflict("异常已终结");
            store.db.update("UPDATE anomaly SET status='DISMISSED',active_dedupe_key=NULL,resolution_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE anomaly_id=?",c.reason(),id);
            events.append("AnomalyDismissed",id,park,actor,Map.of("reason",c.reason()));return Map.of("anomalyId",id,"status","DISMISSED","version",c.expectedVersion()+1);
        });
        for(String incident:store.db.queryForList("SELECT incident_id FROM incident_anomaly_link WHERE anomaly_id=? AND unlinked_at IS NULL",String.class,id))reevaluateClosure(incident);
        return result;
    }
    public Map<String,Object> incidents(AuthorizationContext actor,String status,String priority,String deviceId,String areaId,Instant from,Instant to,int page,int size){
        Set<String> visible=devices.visibleDeviceIds(actor,"incident.read");
        var rows=store.db.queryForList("SELECT * FROM incident ORDER BY opened_at DESC,incident_id").stream().filter(i->{Set<String> ids=incidentDevices(i.get("incident_id").toString());return !ids.isEmpty()&&visible.containsAll(ids)&&(deviceId==null||ids.contains(deviceId))&&(areaId==null||ids.stream().anyMatch(id->devices.device(id).areaId().equals(areaId)));})
                .filter(i->status==null||status.equals(i.get("status"))).filter(i->priority==null||priority.equals(i.get("priority")))
                .filter(i->from==null||!DbTime.instant(i.get("opened_at")).isBefore(from)).filter(i->to==null||DbTime.instant(i.get("opened_at")).isBefore(to)).map(this::incidentSummary).toList();
        return Views.page(rows,page,size);
    }
    private Map<String,Object> incidentSummary(Map<String,Object> raw){var result=Views.row(raw);result.remove("impactSnapshotJson");result.remove("primaryCorrelationKey");return result;}
    public Map<String,Object> detail(AuthorizationContext actor,String id){
        requireIncident(actor,"incident.read",id);var raw=incident(id);var result=incidentSummary(raw);
        result.put("impactSnapshot",parse(raw.get("impact_snapshot_json").toString()));
        result.put("anomalyLinks",store.db.queryForList("SELECT l.*,a.device_id FROM incident_anomaly_link l JOIN anomaly a ON a.anomaly_id=l.anomaly_id WHERE incident_id=? AND unlinked_at IS NULL",id).stream().map(Views::row).toList());
        result.put("evidenceReferences",store.db.queryForList("SELECT e.* FROM anomaly_evidence e JOIN incident_anomaly_link l ON l.anomaly_id=e.anomaly_id WHERE l.incident_id=? AND l.unlinked_at IS NULL ORDER BY e.received_at",id).stream().map(Views::row).toList());
        result.put("correlationCandidates",store.db.queryForList("SELECT * FROM correlation_candidate WHERE incident_id=?",id).stream().map(Views::row).toList());
        var port=orders.getIfAvailable();result.put("workOrders",port==null?List.of():port.forIncident(id));result.put("completeness","COMPLETE");return result;
    }
    public JsonNode create(AuthorizationContext actor,String key,Create c){
        actor.require("incident.review");var anomalies=c.anomalyIds().stream().distinct().map(this::anomaly).toList();if(anomalies.size()!=c.anomalyIds().size())throw ApiProblem.invalid("异常不能重复");
        anomalies.forEach(a->visible(actor,"incident.review",a));String park=anomalies.getFirst().get("park_id").toString();
        if(anomalies.stream().anyMatch(a->!park.equals(a.get("park_id"))))throw ApiProblem.invalid("故障不能跨园区");
        return write(actor,key,park,"IncidentCreated",c,()->{
            var current=c.anomalyIds().stream().map(this::anomaly).toList();if(current.stream().anyMatch(a->!a.get("status").equals("ACTIVE")))throw ApiProblem.conflict("只能关联活动异常");
            String id=createIncident(park,c.title(),c.priority().name(),c.reason(),current,null,actor.userId(),null);return Map.of("incidentId",id,"status","PENDING_REVIEW","version",0);
        });
    }
    public JsonNode review(AuthorizationContext actor,String key,String id,Review c){var i=requireIncident(actor,"incident.review",id);
        return write(actor,key,i.parkId(),"IncidentReviewed",Map.of("id",id,"body",c),()->{
            var current=lockIncident(id);Views.version(current,c.expectedVersion());if(!current.get("status").equals("PENDING_REVIEW"))throw ApiProblem.conflict("故障不在待研判状态");
            String next=c.decision()==Decision.CONFIRM?"OPEN":"REJECTED";
            store.db.update("UPDATE incident SET status=?,confirmed_at=CURRENT_TIMESTAMP(3),confirmed_by=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3),primary_correlation_key=CASE WHEN ?='REJECTED' THEN NULL ELSE primary_correlation_key END WHERE incident_id=?",next,actor.userId(),next,id);
            if(c.decision()==Decision.REJECT){store.db.update("UPDATE incident_work_order_gate SET gate_status='CLOSED',terminal_reason=?,version=version+1 WHERE incident_id=?",c.reason(),id);store.db.update("UPDATE incident_anomaly_link SET unlinked_at=CURRENT_TIMESTAMP(3),unlinked_by=? WHERE incident_id=? AND unlinked_at IS NULL",actor.userId(),id);}
            events.append("IncidentReviewed",id,i.parkId(),actor,Map.of("status",next,"reason",c.reason()));return Map.of("incidentId",id,"status",next,"version",c.expectedVersion()+1);
        });
    }
    public JsonNode priority(AuthorizationContext actor,String key,String id,ChangePriority c){var i=requireIncident(actor,"incident.priority.change",id);
        return write(actor,key,i.parkId(),"IncidentPriorityChanged",Map.of("id",id,"body",c),()->{
            var current=lockIncident(id);Views.version(current,c.expectedVersion());nonterminal(current);
            store.db.update("UPDATE incident SET priority=?,priority_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",c.priority().name(),c.reason(),id);
            events.append("IncidentPriorityChanged",id,i.parkId(),actor,Map.of("priority",c.priority().name(),"reason",c.reason()));return Map.of("incidentId",id,"version",c.expectedVersion()+1);
        });
    }
    public JsonNode cause(AuthorizationContext actor,String key,String id,Cause c){var i=requireIncident(actor,"incident.review",id);
        return write(actor,key,i.parkId(),"IncidentRootCauseReviewed",Map.of("id",id,"body",c),()->{
            var current=lockIncident(id);Views.version(current,c.expectedVersion());nonterminal(current);
            if(c.rootCauseStatus()==RootCause.CONFIRMED&&(c.confirmedRootCauseText()==null||c.confirmedRootCauseText().isBlank()||c.evidenceReferenceIds()==null||c.evidenceReferenceIds().isEmpty()))throw ApiProblem.invalid("确认根因必须有结论和证据");
            for(String evidence:c.evidenceReferenceIds()==null?List.<String>of():c.evidenceReferenceIds())if(!Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM anomaly_evidence e JOIN incident_anomaly_link l ON l.anomaly_id=e.anomaly_id WHERE l.incident_id=? AND e.evidence_id=? AND l.unlinked_at IS NULL",Boolean.class,id,evidence)))throw ApiProblem.invalid("根因证据不属于该故障");
            store.db.update("UPDATE incident SET root_cause_status=?,confirmed_root_cause_text=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",c.rootCauseStatus().name(),c.rootCauseStatus()==RootCause.CONFIRMED?c.confirmedRootCauseText():null,id);
            events.append("IncidentRootCauseReviewed",id,i.parkId(),actor,Map.of("rootCauseStatus",c.rootCauseStatus().name(),"reason",c.reason(),"evidenceReferenceIds",c.evidenceReferenceIds()==null?List.of():c.evidenceReferenceIds()));return Map.of("incidentId",id,"version",c.expectedVersion()+1);
        });
    }
    public JsonNode close(AuthorizationContext actor,String key,String id,Close c){
        var ref=requireIncident(actor,"incident.close",id);
        var replay=commands.replay(ref.parkId(),key,actor.userId(),"IncidentClosed",Map.of("id",id,"body",c));if(replay.isPresent())return replay.get();
        // Close is deliberately split into short local transactions. No repository/network lock spans the query port.
        store.tx.executeWithoutResult(status->{
            var i=lockIncident(id);Views.version(i,c.expectedVersion());nonterminal(i);
            if(!Set.of("OPEN","PROCESSING","PENDING_CLOSE").contains(i.get("status")))throw ApiProblem.conflict("待研判故障必须先确认或拒绝");
            var gate=store.db.queryForMap("SELECT * FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE",id);
            if(!gate.get("gate_status").equals("OPEN"))throw ApiProblem.conflict("故障正在关闭或已关闭");
            store.db.update("UPDATE incident_work_order_gate SET gate_status='CLOSING',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",id);
        });
        try{
            reconcile(id);
            if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM incident_work_order_reservation WHERE incident_id=? AND status IN('RESERVED','RECONCILIATION_REQUIRED')",Boolean.class,id)))throw ApiProblem.conflict("仍有工单创建或重开结果待确认");
            var port=orders.getIfAvailable();if(port==null)throw new ApiProblem(503,"WORKORDER_AUTHORITY_UNAVAILABLE","工单权威查询不可用");
            var actual=port.forIncident(id);if(actual.stream().anyMatch(o->!Set.of("CLOSED","CANCELLED").contains(o.status())))throw ApiProblem.conflict("仍有关联工单未终结");
            return write(actor,key,ref.parkId(),"IncidentClosed",Map.of("id",id,"body",c),()->{
                var i=lockIncident(id);Views.version(i,c.expectedVersion());
                if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM anomaly a JOIN incident_anomaly_link l ON l.anomaly_id=a.anomaly_id WHERE l.incident_id=? AND l.unlinked_at IS NULL AND a.status='ACTIVE'",Boolean.class,id)))throw ApiProblem.conflict("仍有活动异常，不能关闭故障");
                if(!store.db.queryForObject("SELECT gate_status FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE",String.class,id).equals("CLOSING"))throw ApiProblem.conflict("关闭门禁已改变");
                store.db.update("UPDATE incident SET status='CLOSED',closed_at=CURRENT_TIMESTAMP(3),closed_by=?,resolved_disposition=?,primary_correlation_key=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",actor.userId(),c.resolvedDisposition(),id);
                store.db.update("UPDATE incident_work_order_gate SET gate_status='CLOSED',terminal_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",c.reason(),id);
                events.append("IncidentClosed",id,ref.parkId(),actor,Map.of("reason",c.reason(),"resolvedDisposition",c.resolvedDisposition()));return Map.of("incidentId",id,"status","CLOSED","version",c.expectedVersion()+1);
            });
        }catch(RuntimeException failure){store.db.update("UPDATE incident_work_order_gate SET gate_status='OPEN',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=? AND gate_status='CLOSING'",id);throw failure;}
    }
    @Override public Reservation reserve(AuthorizationContext actor,String id,String operation,String key,String requestId,String hash){
        requireIncident(actor,operation.equals("CREATE")?"workorder.create":"workorder.reopen",id);
        if(!Set.of("CREATE","REOPEN").contains(operation)||key==null||key.length()>128||requestId==null||requestId.length()>128)throw ApiProblem.invalid("工单 reservation 参数无效");
        return store.tx.execute(status->{
            var i=lockIncident(id);var prior=store.db.queryForList("SELECT * FROM incident_work_order_reservation WHERE incident_id=? AND idempotency_key=?",id,key);
            if(!prior.isEmpty()){
                var p=prior.getFirst();if(!hash.equals(p.get("request_hash"))||!actor.userId().equals(p.get("actor_user_id"))||!operation.equals(p.get("operation_type")))throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT","门禁幂等键内容冲突");
                if(p.get("status").equals("RELEASED"))throw ApiProblem.conflict("此前许可已释放，请使用新的幂等键");return reservation(p);
            }
            String gate=store.db.queryForObject("SELECT gate_status FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE",String.class,id);
            if(!gate.equals("OPEN"))throw ApiProblem.conflict("故障门禁已阻止新建或重开");
            if(operation.equals("CREATE")&&!Set.of("OPEN","PROCESSING").contains(i.get("status")))throw ApiProblem.conflict("该故障尚未确认或已不允许创建工单");
            nonterminal(i);
            if(operation.equals("REOPEN")&&i.get("status").equals("PENDING_CLOSE"))store.db.update("UPDATE incident SET status='PROCESSING',version=version+1 WHERE incident_id=?",id);
            String reservation=UUID.randomUUID().toString();store.db.update("INSERT INTO incident_work_order_reservation(reservation_id,incident_id,request_id,idempotency_key,actor_user_id,request_hash,operation_type) VALUES(?,?,?,?,?,?,?)",reservation,id,requestId,key,actor.userId(),hash,operation);
            return new Reservation(reservation,id,"RESERVED",null);
        });
    }
    @Override public void commit(String reservationId,long order){store.tx.executeWithoutResult(status->{
        var rows=store.db.queryForList("SELECT incident_id FROM incident_work_order_reservation WHERE reservation_id=?",String.class,reservationId);if(rows.isEmpty())throw ApiProblem.missing();String id=rows.getFirst();lockIncident(id);
        store.db.update("UPDATE incident_work_order_reservation SET status='COMMITTED',work_order_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE reservation_id=? AND status IN('RESERVED','RECONCILIATION_REQUIRED')",order,reservationId);
        store.db.update("UPDATE incident SET status='PROCESSING',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=? AND status='OPEN'",id);
    });}
    @Override public void releaseRolledBack(String reservationId,String reason){store.db.update("UPDATE incident_work_order_reservation SET status='RELEASED',failure_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE reservation_id=? AND status='RESERVED'",reason,reservationId);}
    @Override public void markUncertain(String reservationId){store.db.update("UPDATE incident_work_order_reservation SET status='RECONCILIATION_REQUIRED',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE reservation_id=? AND status='RESERVED'",reservationId);}
    public void reconcile(String incident){
        var port=orders.getIfAvailable();if(port==null)return;
        for(var reservation:store.db.queryForList("SELECT reservation_id FROM incident_work_order_reservation WHERE incident_id=? AND status IN('RESERVED','RECONCILIATION_REQUIRED')",String.class,incident)) {
            // Absence is not proof that an in-flight transaction cannot commit. Never release by TTL.
            port.forReservation(reservation).ifPresent(order->commit(reservation,order.workOrderId()));
        }
    }
    public void recoverClosingGates(){
        for(String id:store.db.queryForList("SELECT incident_id FROM incident_work_order_gate WHERE gate_status='CLOSING' AND updated_at<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(3))",String.class)) {
            reconcile(id);store.tx.executeWithoutResult(s->{var i=lockIncident(id);if(!Set.of("CLOSED","REJECTED").contains(i.get("status")))store.db.update("UPDATE incident_work_order_gate SET gate_status='OPEN',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=? AND gate_status='CLOSING'",id);});
        }
    }
    public void workOrderEvent(JsonNode event){
        JsonNode body=event.path("body");if(!body.path("sourceType").asText().equals("INCIDENT")||body.path("incidentId").asText().isBlank())return;
        String incidentId=body.path("incidentId").asText(),eventId=event.path("eventId").asText();long orderId=Long.parseLong(body.path("workOrderId").asText()),version=body.path("aggregateVersion").asLong();
        String orderStatus=body.path("status").asText();var port=orders.getIfAvailable();if(port==null)throw new ApiProblem(503,"WORKORDER_AUTHORITY_UNAVAILABLE","工单权威查询不可用");
        var previous=store.db.queryForList("SELECT aggregate_version FROM incident_work_order_projection WHERE work_order_id=?",Long.class,orderId);
        if(!previous.isEmpty()&&version>previous.getFirst()+1){
            var actual=port.forIncident(incidentId).stream().filter(o->o.workOrderId()==orderId).findFirst().orElseThrow(ApiProblem::missing);version=actual.version();orderStatus=actual.status();
        }
        final long currentVersion=version;final String currentStatus=orderStatus;
        store.tx.executeWithoutResult(status->{
            lockIncident(incidentId);
            if(store.db.update("INSERT IGNORE INTO inbox_event(consumer_group,event_id) VALUES('fault-workorder-v1',?)",eventId)==0)return;
            var revisions=store.db.queryForList("SELECT aggregate_version FROM incident_work_order_projection WHERE work_order_id=? FOR UPDATE",Long.class,orderId);
            if(!revisions.isEmpty()&&revisions.getFirst()>=currentVersion)return;
            store.db.update("INSERT INTO incident_work_order_projection(work_order_id,incident_id,status,aggregate_version) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),aggregate_version=VALUES(aggregate_version),updated_at=CURRENT_TIMESTAMP(3)",orderId,incidentId,currentStatus,currentVersion);
        });
        reevaluateClosure(incidentId);
    }
    public void reevaluateClosure(String id){
        var port=orders.getIfAvailable();if(port==null)return;var actual=port.forIncident(id);
        store.tx.executeWithoutResult(status->{
            var i=lockIncident(id);if(!Set.of("OPEN","PROCESSING","PENDING_CLOSE").contains(i.get("status")))return;
            boolean active=Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM anomaly a JOIN incident_anomaly_link l ON l.anomaly_id=a.anomaly_id WHERE l.incident_id=? AND l.unlinked_at IS NULL AND a.status='ACTIVE'",Boolean.class,id));
            boolean unresolved=Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM incident_work_order_reservation WHERE incident_id=? AND status IN('RESERVED','RECONCILIATION_REQUIRED')",Boolean.class,id));
            boolean completed=actual.stream().allMatch(o->Set.of("CLOSED","CANCELLED").contains(o.status()));
            String next=!active&&!unresolved&&completed?"PENDING_CLOSE":actual.isEmpty()?"OPEN":"PROCESSING";
            if(!i.get("status").equals(next)){
                store.db.update("UPDATE incident SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",next,id);
                events.append("IncidentReadinessChanged",id,i.get("park_id").toString(),null,Map.of("status",next,"reason",next.equals("PENDING_CLOSE")?"异常处置与工单条件齐备，仍需主管最终核查":"关闭准备条件已失效"));
            }
        });
    }
    private Reservation reservation(Map<String,Object> r){return new Reservation(r.get("reservation_id").toString(),r.get("incident_id").toString(),r.get("status").toString(),r.get("work_order_id")==null?null:((Number)r.get("work_order_id")).longValue());}
    private Map<String,Object> lockIncident(String id){var rows=store.db.queryForList("SELECT * FROM incident WHERE incident_id=? FOR UPDATE",id);if(rows.isEmpty())throw ApiProblem.missing();return rows.getFirst();}
    private static void nonterminal(Map<String,Object> i){if(Set.of("CLOSED","REJECTED").contains(i.get("status")))throw ApiProblem.conflict("故障已终结");}
    private JsonNode write(AuthorizationContext actor,String key,String park,String type,Object command,Supplier<?> mutation){
        store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES(?)",park);
        return commands.execute(park,key,actor.userId(),type,command,mutation);
    }
    private String createIncident(String park,String title,String priority,String reason,List<Map<String,Object>> anomalies,String root,String actor,String correlation){
        String id=UUID.randomUUID().toString();var deviceIds=anomalies.stream().map(a->a.get("device_id").toString()).distinct().sorted().toList();
        store.db.update("INSERT INTO incident(incident_id,incident_no,park_id,title,status,priority,priority_reason,root_cause_status,candidate_root_device_id,impact_snapshot_json,primary_correlation_key,opened_at,created_by) VALUES(?,?,?,?,'PENDING_REVIEW',?,?,?,?,?,?,CURRENT_TIMESTAMP(3),?)",id,"INC-"+id.substring(0,12).toUpperCase(Locale.ROOT),park,title,priority,reason,root==null?"UNKNOWN":"UNCONFIRMED",root,serialize(Map.of("actualDeviceIds",deviceIds,"possibleImpactDeviceIds",List.of())),correlation,actor);
        store.db.update("INSERT INTO incident_work_order_gate(incident_id) VALUES(?)",id);
        for(var anomaly:anomalies)store.db.update("INSERT INTO incident_anomaly_link(link_id,incident_id,anomaly_id,relation_kind,confidence,linked_at,linked_by,evidence_json) VALUES(?,?,?,'PRIMARY',?,CURRENT_TIMESTAMP(3),?,?)",UUID.randomUUID().toString(),id,anomaly.get("anomaly_id"),root==null?1.0:0.95,actor,serialize(Map.of("source","ANOMALY_EVIDENCE","candidateOnly",root!=null)));
        events.append("IncidentCreated",id,park,null,Map.of("incidentId",id,"rootCauseStatus",root==null?"UNKNOWN":"UNCONFIRMED"));return id;
    }
    public void observation(JsonNode event){
        String eventId=event.path("eventId").asText(),type=event.path("eventType").asText(),deviceId=event.path("aggregateId").asText(),park=event.path("parkId").asText();JsonNode body=event.path("body");
        if(!Set.of("DeviceConnectionLost","DeviceConnectionRestored","DeviceObservationAccepted").contains(type))return;
        var device=devices.device(deviceId);if(!device.parkId().equals(park))throw ApiProblem.invalid("观察园区不一致");
        boolean applied=Boolean.TRUE.equals(store.tx.execute(status->{
            store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES(?)",park);store.db.queryForObject("SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE",String.class,park);
            if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM inbox_event WHERE consumer_group='fault-observation-v1' AND event_id=?",Boolean.class,eventId)))return false;
            store.db.update("INSERT INTO inbox_event(consumer_group,event_id) VALUES('fault-observation-v1',?)",eventId);
            String stream,anomalyType=null,resource="device";boolean recovering=false;long epoch,sequence;
            Instant received=body.has("receivedAt")?Instant.parse(body.path("receivedAt").asText()):Instant.parse(event.path("occurredAt").asText());
            Instant occurred=body.has("occurredAt")?Instant.parse(body.path("occurredAt").asText()):received;
            if(Set.of("DeviceConnectionLost","DeviceConnectionRestored").contains(type)) {
                stream="CONNECTIVITY";epoch=0;sequence=body.path("stateVersion").asLong();anomalyType="DEVICE_CONNECTION_LOST";recovering=type.equals("DeviceConnectionRestored");
            }else if(type.equals("DeviceObservationAccepted")) {
                stream=body.path("streamKey").asText();epoch=body.path("sessionEpoch").asLong();sequence=body.path("sequence").asLong();
                var payload=body.path("payload");resource=payload.path("resourceKey").asText();String eventType=body.path("eventType").asText();
                if(Set.of("DEVICE_FAULT","DEVICE_RECOVERED").contains(eventType)){anomalyType=payload.path("faultCode").asText();recovering=eventType.equals("DEVICE_RECOVERED");}
                else if(eventType.equals("STATUS_OBSERVATION")&&payload.path("quality").asText().equals("GOOD")){
                    String value=payload.path("value").asText();
                    switch(payload.path("observationType").asText()){
                        case "VIDEO_SIGNAL"->{if(Set.of("LOST","PRESENT").contains(value)){anomalyType="CAMERA_NO_VIDEO";recovering=value.equals("PRESENT");}}
                        case "RECORDING_STATUS"->{if(Set.of("NOT_RECORDING","RECORDING").contains(value)){anomalyType="NVR_RECORDING_STOPPED";recovering=value.equals("RECORDING");}}
                        case "LINK_STATUS"->{if(Set.of("DOWN","UP").contains(value)){anomalyType="SWITCH_PORT_LINK_DOWN";recovering=value.equals("UP");}}
                        case "POE_STATUS"->{if(Set.of("UNPOWERED","FAULT","POWERED").contains(value)){anomalyType="SWITCH_POE_FAULT";recovering=value.equals("POWERED");}}
                        default->{}
                    }
                }
            }else return false;
            var cursors=store.db.queryForList("SELECT * FROM fault_observation_cursor WHERE device_id=? AND stream_key=?",deviceId,stream);
            if(!cursors.isEmpty()){
                var prior=cursors.getFirst();long oldEpoch=((Number)prior.get("session_epoch")).longValue(),oldSeq=((Number)prior.get("sequence_no")).longValue();
                if(epoch<oldEpoch||epoch==oldEpoch&&sequence<=oldSeq)return false;
            }
            store.db.update("INSERT INTO fault_observation_cursor(device_id,stream_key,session_epoch,sequence_no,received_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE session_epoch=VALUES(session_epoch),sequence_no=VALUES(sequence_no),received_at=VALUES(received_at)",deviceId,stream,epoch,sequence,DbTime.sql(received));
            if(type.equals("DeviceObservationAccepted")&&(!body.path("eventType").asText().equals("STATUS_OBSERVATION")||body.path("payload").path("quality").asText().equals("GOOD")&&!body.path("payload").path("value").asText().equals("UNKNOWN")))
                store.db.update("INSERT INTO fault_device_observation(device_id,last_good_observed_at) VALUES(?,?) ON DUPLICATE KEY UPDATE last_good_observed_at=GREATEST(last_good_observed_at,VALUES(last_good_observed_at))",deviceId,DbTime.sql(received));
            if(anomalyType==null)return false;
            String dedupe=sha("DEVICE|"+deviceId+"|"+anomalyType+"|"+resource);
            var active=store.db.queryForList("SELECT * FROM anomaly WHERE park_id=? AND active_dedupe_key=? FOR UPDATE",park,dedupe);
            if(recovering&&active.isEmpty())return false;
            String anomalyId;
            if(active.isEmpty()){
                anomalyId=UUID.randomUUID().toString();var previous=store.db.queryForList("SELECT anomaly_id FROM anomaly WHERE park_id=? AND device_id=? AND anomaly_type=? AND resource_key=? ORDER BY last_observed_at DESC LIMIT 1",String.class,park,deviceId,anomalyType,resource);
                store.db.update("INSERT INTO anomaly(anomaly_id,park_id,subject_kind,subject_id,device_id,anomaly_type,resource_key,active_dedupe_key,status,severity,first_observed_at,last_observed_at,previous_anomaly_id) VALUES(?,?,'DEVICE',?,?,?,?,?,'ACTIVE','FAULT',?,?,?)",anomalyId,park,deviceId,deviceId,anomalyType,resource,dedupe,DbTime.sql(occurred),DbTime.sql(received),previous.isEmpty()?null:previous.getFirst());
            }else{
                var old=active.getFirst();anomalyId=old.get("anomaly_id").toString();
                // A recovery from another stream must still be newer than the active fault evidence.
                if(recovering&&received.isBefore(DbTime.instant(old.get("last_observed_at"))))return false;
                store.db.update("UPDATE anomaly SET status=?,active_dedupe_key=?,last_observed_at=GREATEST(last_observed_at,?),recovered_at=?,observation_count=observation_count+1,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE anomaly_id=?",recovering?"RECOVERED":"ACTIVE",recovering?null:dedupe,DbTime.sql(received),recovering?DbTime.sql(received):null,anomalyId);
            }
            String evidence=UUID.randomUUID().toString();store.db.update("INSERT INTO anomaly_evidence(evidence_id,anomaly_id,source_context,source_type,source_id,event_id,evidence_type,occurred_at,received_at,summary_json) VALUES(?,?,'DEVICE',?,?,?,?,?,?,?)",evidence,anomalyId,type,eventId,body.path("eventId").isTextual()?body.path("eventId").asText():null,recovering?"RECOVERY":"OBSERVATION",DbTime.sql(occurred),DbTime.sql(received),serialize(Map.of("deviceId",deviceId,"anomalyType",anomalyType,"resourceKey",resource,"streamKey",stream,"sequence",sequence)));
            store.db.update("UPDATE anomaly SET last_evidence_ref=? WHERE anomaly_id=?",evidence,anomalyId);
            events.append(recovering?"AnomalyRecovered":"AnomalyObserved",anomalyId,park,null,Map.of("deviceId",deviceId,"anomalyId",anomalyId));return true;
        }));
        if(applied){
            correlate(park);
            for(String id:store.db.queryForList("SELECT DISTINCT l.incident_id FROM incident_anomaly_link l JOIN anomaly a ON a.anomaly_id=l.anomaly_id WHERE a.device_id=? AND l.unlinked_at IS NULL",String.class,deviceId))reevaluateClosure(id);
        }
    }
    public void correlate(String park){
        // Read topology before the short Fault transaction. The candidate records its evidence snapshot.
        Instant at=Instant.now();Object observed=store.db.queryForObject("SELECT MAX(first_observed_at) FROM anomaly WHERE park_id=? AND status='ACTIVE' AND anomaly_type='DEVICE_CONNECTION_LOST'",Object.class,park);
        Instant asOf=observed==null?at:DbTime.instant(observed);var network=devices.networkRelations(park,asOf);Map<String,String> parent=new HashMap<>();for(var edge:network)parent.put(edge.get("downstreamDeviceId").toString(),edge.get("upstreamSwitchId").toString());
        store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES(?)",park);
        store.tx.executeWithoutResult(status->{
            store.db.queryForObject("SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE",String.class,park);
            var anomalies=store.db.queryForList("SELECT a.* FROM anomaly a WHERE a.park_id=? AND a.status='ACTIVE' AND a.anomaly_type IN('DEVICE_CONNECTION_LOST','SWITCH_DEVICE_ERROR','SWITCH_OVERHEAT') AND a.last_observed_at>TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) AND NOT EXISTS(SELECT 1 FROM incident_anomaly_link l WHERE l.anomaly_id=a.anomaly_id AND l.unlinked_at IS NULL)",park,-correlationSeconds);
            for(var root:anomalies){String rootId=root.get("device_id").toString();if(!network.stream().anyMatch(edge->rootId.equals(edge.get("upstreamSwitchId"))))continue;
                var group=new ArrayList<Map<String,Object>>();group.add(root);
                for(var candidate:anomalies){String candidateId=candidate.get("device_id").toString();
                    if(candidate.get("anomaly_type").equals("DEVICE_CONNECTION_LOST")&&!candidateId.equals(rootId)&&rootId.equals(parent.get(candidateId)))group.add(candidate);
                }
                if(group.size()<3)continue;
                String correlation="NETWORK/"+root.get("anomaly_id");
                if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM incident WHERE park_id=? AND primary_correlation_key=?",Boolean.class,park,correlation)))continue;
                if(group.stream().anyMatch(a->Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM incident_anomaly_link WHERE anomaly_id=? AND unlinked_at IS NULL",Boolean.class,a.get("anomaly_id")))))continue;
                String id=createIncident(park,"交换机及下联设备通信异常（待研判）","P2","共同上游、交换机自身异常与下联设备失联的相近时间证据；并非已确认根因",group,rootId,"fault-correlation",correlation);
                store.db.update("INSERT INTO correlation_candidate(candidate_id,incident_id,candidate_root_device_id,related_anomaly_ids_json,confidence,evidence_path_json,generated_at,expires_at) VALUES(?,?,?,?,0.9500,?,?,?)",UUID.randomUUID().toString(),id,rootId,serialize(group.stream().map(a->a.get("anomaly_id")).toList()),serialize(network.stream().filter(edge->group.stream().anyMatch(a->a.get("device_id").equals(edge.get("downstreamDeviceId")))).toList()),DbTime.sql(at),DbTime.sql(at.plusSeconds(300)));
            }
        });
    }
    private static String sha(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
