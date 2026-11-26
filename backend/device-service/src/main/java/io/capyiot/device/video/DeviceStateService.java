package io.capyiot.device.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
public class DeviceStateService {
    private final AssetStore store;
    private final DeviceSessions sessions;
    private final ObjectMapper json;
    private final LocalEvents events;
    public DeviceStateService(AssetStore store,DeviceSessions sessions,ObjectMapper json){this.store=store;this.sessions=sessions;this.json=json;events=new LocalEvents(store.db,json,"device");}
    public String accept(VideoDeviceEvent e,int partition,long offset) {
        return store.tx.execute(status->{
            var devices=store.db.queryForList("SELECT * FROM device WHERE id=? FOR UPDATE",e.deviceId());if(devices.isEmpty())throw ApiProblem.missing();
            var context=sessions.current(e.deviceId(),e.sessionId());
            if(e.sessionEpoch()!=context.sessionEpoch()||e.credentialVersion()!=context.credentialVersion()||!e.parkId().equals(context.parkId())||!e.deviceType().equals(context.deviceType())||!e.source().equals(context.source()))throw ApiProblem.invalid("事件会话上下文已失效");
            String topic="capyiot/v1/devices/"+e.deviceId()+"/sessions/"+e.sessionId()+"/events";
            var wire=new VideoDeviceMessage(e.eventId(),e.schemaVersion(),e.deviceId(),e.sessionId(),e.eventType(),e.streamKey(),e.sequence(),e.occurredAt(),e.payload());
            VideoDeviceMessage.validate(json.valueToTree(wire),topic,e.deviceType(),e.receivedAt());
            if(e.payload().has("monitoringPointId")&&!Objects.equals(context.monitoringPointId(),e.payload().path("monitoringPointId").asText()))throw ApiProblem.invalid("观察点位与当前 IPC 绑定不一致");
            String hash=hash(wire);var receipts=store.db.queryForList("SELECT envelope_hash FROM device_event_receipt WHERE source=? AND event_id=?",e.source(),e.eventId());
            if(!receipts.isEmpty()){
                if(!hash.equals(receipts.getFirst().get("envelope_hash")))throw new ApiProblem(409,"EVENT_ID_CONFLICT","同一事件 ID 内容不同");
                return "DUPLICATE";
            }
            store.db.update("INSERT IGNORE INTO device_runtime_state(device_id) VALUES(?)",e.deviceId());
            var state=store.db.queryForMap("SELECT * FROM device_runtime_state WHERE device_id=? FOR UPDATE",e.deviceId());
            var cursors=store.db.queryForList("SELECT last_sequence FROM device_stream_cursor WHERE device_id=? AND session_epoch=? AND stream_key=?",e.deviceId(),e.sessionEpoch(),e.streamKey());
            boolean stale=!cursors.isEmpty()&&((Number)cursors.getFirst().get("last_sequence")).longValue()>=e.sequence();
            String result=stale?"IGNORED_STALE":"APPLIED";
            store.db.update("INSERT INTO device_event_receipt(source,event_id,envelope_hash,device_id,processing_status,received_at) VALUES(?,?,?,?,?,?)",e.source(),e.eventId(),hash,e.deviceId(),result,ts(e.receivedAt()));
            if(!e.eventType().equals("HEARTBEAT"))store.db.update("""
                INSERT INTO device_event(event_id,campus_id,device_id,event_type,session_epoch,seq,occurred_at,received_at,trace_id,source,processing_status,payload_json,envelope_hash,kafka_partition,kafka_offset,schema_version,stream_key,sequence_no)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,e.eventId(),e.parkId(),e.deviceId(),e.eventType(),e.sessionEpoch(),e.sequence(),ts(e.occurredAt()),ts(e.receivedAt()),e.traceId(),e.source(),result,serialize(e.payload()),hash,partition,offset,Integer.toString(e.schemaVersion()),e.streamKey(),e.sequence());
            if(stale)return result;
            store.db.update("INSERT INTO device_stream_cursor(device_id,session_epoch,stream_key,last_sequence,last_event_id,last_envelope_hash,received_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE last_sequence=VALUES(last_sequence),last_event_id=VALUES(last_event_id),last_envelope_hash=VALUES(last_envelope_hash),received_at=VALUES(received_at)",e.deviceId(),e.sessionEpoch(),e.streamKey(),e.sequence(),e.eventId(),hash,ts(e.receivedAt()));
            long version=((Number)state.get("version")).longValue();boolean changed=false;
            if(e.eventType().equals("HEARTBEAT")) {
                var previous=DbTime.instant(state.get("last_heartbeat_received_at"));
                if(previous==null||e.receivedAt().isAfter(previous)) {
                    if(!state.get("connectivity_status").equals("ONLINE")){
                        version++;transition(e,"CONNECTIVITY",state.get("connectivity_status").toString(),"ONLINE",version);changed=true;
                        events.append("DeviceConnectionRestored",e.deviceId(),e.parkId(),null,Map.of("eventId",e.eventId(),"stateVersion",version,"receivedAt",e.receivedAt()));
                    }
                    store.db.update("UPDATE device_runtime_state SET connectivity_status='ONLINE',last_heartbeat_received_at=?,heartbeat_generation=heartbeat_generation+1,session_epoch=?,version=?,updated_at=? WHERE device_id=?",ts(e.receivedAt()),e.sessionEpoch(),version,ts(e.receivedAt()),e.deviceId());
                }
            } else {
                var previous=DbTime.instant(state.get("last_business_data_received_at"));
                boolean fresh=previous==null||e.receivedAt().isAfter(previous);
                if(fresh) {
                    if(!state.get("data_freshness").equals("FRESH")){version++;transition(e,"DATA_FRESHNESS",state.get("data_freshness").toString(),"FRESH",version);changed=true;}
                    String mode=state.get("operating_mode").toString();
                    if(e.eventType().equals("STATUS_OBSERVATION")&&e.payload().path("observationType").asText().equals("OPERATING_MODE")&&e.payload().path("quality").asText().equals("GOOD")) {
                        String value=e.payload().path("value").asText();if(!mode.equals(value)){version++;transition(e,"OPERATING_MODE",mode,value,version);mode=value;changed=true;}
                    }
                    store.db.update("UPDATE device_runtime_state SET operating_mode=?,data_freshness='FRESH',last_business_data_received_at=?,session_epoch=?,version=?,updated_at=? WHERE device_id=?",mode,ts(e.receivedAt()),e.sessionEpoch(),version,ts(e.receivedAt()),e.deviceId());
                }
                events.append("DeviceObservationAccepted",e.deviceId(),e.parkId(),null,Map.of("eventId",e.eventId(),"eventType",e.eventType(),"payload",e.payload(),"streamKey",e.streamKey(),"sequence",e.sequence(),"sessionEpoch",e.sessionEpoch(),"occurredAt",e.occurredAt(),"receivedAt",e.receivedAt(),"stateVersion",version));
            }
            if(changed)events.append("DeviceRuntimeStateChanged",e.deviceId(),e.parkId(),null,Map.of("eventId",e.eventId(),"stateVersion",version,"streamKey",e.streamKey(),"sessionEpoch",e.sessionEpoch(),"sequence",e.sequence(),"receivedAt",e.receivedAt(),"connectivityStatus",e.eventType().equals("HEARTBEAT")?"ONLINE":state.get("connectivity_status")));
            return result;
        });
    }
    private void transition(VideoDeviceEvent e,String dimension,String old,String next,long version) {
        store.db.update("INSERT INTO device_status_history(device_id,state_dimension,old_value,new_value,source_event_id,occurred_at,received_at,stream_key,stream_sequence,state_version) VALUES(?,?,?,?,?,?,?,?,?,?)",e.deviceId(),dimension,old,next,e.eventId(),ts(e.occurredAt()),ts(e.receivedAt()),e.streamKey(),e.sequence(),version);
    }
    public int expire(Instant now,long heartbeatSeconds,long freshnessSeconds) {
        if(heartbeatSeconds<1||freshnessSeconds<1)throw new IllegalArgumentException("Invalid state timeout");
        int changed=0;
        for(var item:store.db.queryForList("SELECT s.device_id FROM device_runtime_state s JOIN device d ON d.id=s.device_id WHERE d.asset_status='IN_SERVICE' ORDER BY s.device_id")) {
            boolean result=Boolean.TRUE.equals(store.tx.execute(status->{
                String id=item.get("device_id").toString();var d=store.db.queryForMap("SELECT campus_id FROM device WHERE id=? FOR UPDATE",id);
                var s=store.db.queryForMap("SELECT * FROM device_runtime_state WHERE device_id=? FOR UPDATE",id);long version=((Number)s.get("version")).longValue();long original=version;
                Instant hb=DbTime.instant(s.get("last_heartbeat_received_at")),business=DbTime.instant(s.get("last_business_data_received_at"));
                if(hb!=null&&hb.plusSeconds(heartbeatSeconds).isBefore(now)&&s.get("connectivity_status").equals("ONLINE")) {
                    version++;serverTransition(id,"CONNECTIVITY","ONLINE","OFFLINE",now,version);
                    store.db.update("UPDATE device_runtime_state SET connectivity_status='OFFLINE' WHERE device_id=?",id);
                    events.append("DeviceConnectionLost",id,d.get("campus_id").toString(),null,Map.of("stateVersion",version,"heartbeatGeneration",s.get("heartbeat_generation"),"lastHeartbeatReceivedAt",hb,"receivedAt",now));
                }
                if(business!=null&&business.plusSeconds(freshnessSeconds).isBefore(now)&&s.get("data_freshness").equals("FRESH")) {
                    version++;serverTransition(id,"DATA_FRESHNESS","FRESH","STALE",now,version);
                    store.db.update("UPDATE device_runtime_state SET data_freshness='STALE' WHERE device_id=?",id);
                }
                if(version!=original)store.db.update("UPDATE device_runtime_state SET version=?,updated_at=? WHERE device_id=?",version,ts(now),id);
                return version!=original;
            }));if(result)changed++;
        }
        return changed;
    }
    private void serverTransition(String id,String dimension,String old,String next,Instant at,long version){store.db.update("INSERT INTO device_status_history(device_id,state_dimension,old_value,new_value,occurred_at,received_at,state_version) VALUES(?,?,?,?,?,?,?)",id,dimension,old,next,ts(at),ts(at),version);}
    private String hash(Object value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.sorted(json.valueToTree(value)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static java.time.LocalDateTime ts(Instant time){return DbTime.sql(time);}
}
