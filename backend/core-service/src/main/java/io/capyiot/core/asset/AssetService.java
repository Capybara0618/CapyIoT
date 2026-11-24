package io.capyiot.core.asset;

import static io.capyiot.core.asset.AssetModels.*;
import com.fasterxml.jackson.databind.*;
import io.capyiot.core.*;
import io.capyiot.core.platform.*;
import io.capyiot.core.ports.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
public class AssetService implements AssetScopePort,DeviceQueryPort,EvidenceQueryPort {
    private final AssetStore store;
    private final ObjectMapper json;
    private final CommandJournal commands;
    private final LocalEvents events;
    private final ObjectProvider<AssignmentQueryPort> assignments;
    private final ObjectProvider<HealthQueryPort> health;
    public AssetService(AssetStore store,ObjectMapper json,ObjectProvider<AssignmentQueryPort> assignments,ObjectProvider<HealthQueryPort> health,
                        @Value("${capyiot.foundation.service-key}") String key) {
        this.store=store; this.json=json; this.assignments=assignments; this.health=health;
        commands=new CommandJournal(store.db,store.tx,json,key); events=new LocalEvents(store.db,json,"device");
    }
    @Override public boolean parkExists(String id) { return Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM park WHERE park_id=? AND status='ACTIVE'",Boolean.class,id)); }
    @Override public boolean areasBelongToPark(String park,List<String> ids) {
        return ids.stream().allMatch(id->Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM area WHERE id=? AND park_id=? AND status='ACTIVE'",Boolean.class,id,park)));
    }
    @Override public boolean auditVisible(AuthorizationContext actor,String park,List<String> devices,List<String> areas) {
        if(devices.isEmpty()&&areas.isEmpty())return false;
        try {
            if(!devices.isEmpty())for(var device:requireVisibleDevices(actor,"platform.audit.read",devices))if(!device.parkId().equals(park))return false;
            for(String id:areas)requireArea(actor,"platform.audit.read",park,id);
            return true;
        }catch(ApiProblem hidden){return false;}
    }
    private Map<String,Object> area(String id) {
        var rows=store.db.queryForList("SELECT * FROM area WHERE id=?",id); if(rows.isEmpty()) throw ApiProblem.missing(); return rows.getFirst();
    }
    private Map<String,Object> row(String id) {
        var rows=store.db.queryForList("SELECT * FROM device WHERE id=? AND legacy_only=FALSE AND device_type IS NOT NULL",id);
        if(rows.isEmpty()) throw ApiProblem.missing(); return rows.getFirst();
    }
    @Override public DeviceRef device(String id) {
        var d=row(id); return new DeviceRef(id,str(d,"campus_id"),str(d,"area_id"),str(d,"device_code"),str(d,"device_type"),str(d,"name"),str(d,"asset_status"));
    }
    private Set<String> descendants(String park,Collection<String> roots) {
        var areas=store.db.queryForList("SELECT id,parent_area_id FROM area WHERE park_id=?",park);
        Set<String> result=new HashSet<>(roots); boolean changed;
        do { changed=false; for(var a:areas) if(result.contains(a.get("parent_area_id"))) changed|=result.add(str(a,"id")); } while(changed);
        return result;
    }
    private Set<String> visibleAreas(AuthorizationContext actor,String permission,String park) {
        Set<String> result=new HashSet<>();
        for(var grant:actor.require(permission)) {
            if(!Objects.equals(park,grant.parkId())) continue;
            switch(grant.scopeType()) {
                case "PARK" -> result.addAll(store.db.queryForList("SELECT id FROM area WHERE park_id=?",String.class,park));
                case "AREA_SUBTREE" -> result.addAll(descendants(park,grant.rootAreaIds()));
                case "ASSIGNED_TO_ME" -> {
                    var port=assignments.getIfAvailable();
                    if(port!=null) for(String id:port.assignedDeviceIds(actor.userId(),park,grant.assignmentRelation())) {
                        try { result.add(device(id).areaId()); } catch(ApiProblem ignored) { }
                    }
                }
                default -> { }
            }
        }
        return result;
    }
    private void requireArea(AuthorizationContext actor,String permission,String park,String area) {
        if(!visibleAreas(actor,permission,park).contains(area)) throw ApiProblem.missing();
    }
    @Override public Set<String> visibleDeviceIds(AuthorizationContext actor,String permission) {
        Set<String> visible=new HashSet<>();
        for(var grant:actor.require(permission)) {
            if(grant.parkId()==null) continue;
            if(grant.scopeType().equals("ASSIGNED_TO_ME")) {
                var port=assignments.getIfAvailable();
                if(port!=null) visible.addAll(port.assignedDeviceIds(actor.userId(),grant.parkId(),grant.assignmentRelation()));
                continue;
            }
            Set<String> areas=grant.scopeType().equals("AREA_SUBTREE")?descendants(grant.parkId(),grant.rootAreaIds()):null;
            if(!grant.scopeType().equals("PARK")&&areas==null) continue;
            for(var d:store.db.queryForList("SELECT id,area_id FROM device WHERE campus_id=? AND NOT legacy_only AND device_type IS NOT NULL",grant.parkId()))
                if(areas==null||areas.contains(d.get("area_id"))) visible.add(str(d,"id"));
        }
        return Set.copyOf(visible);
    }
    @Override public List<DeviceRef> requireVisibleDevices(AuthorizationContext actor,String permission,Collection<String> ids) {
        Set<String> visible=visibleDeviceIds(actor,permission); if(!visible.containsAll(ids)) throw ApiProblem.missing();
        return ids.stream().map(this::device).toList();
    }
    public List<Map<String,Object>> parks(AuthorizationContext actor) {
        Set<String> parks=actor.require("device.read").stream().map(AuthorizationContext.Grant::parkId).filter(Objects::nonNull).collect(Collectors.toSet());
        return store.db.queryForList("SELECT park_id AS parkId,park_code AS parkCode,name,status,version FROM park ORDER BY park_id").stream().filter(p->parks.contains(p.get("parkId"))).toList();
    }
    public List<Map<String,Object>> areas(AuthorizationContext actor,String park,String parent) {
        Set<String> visible=visibleAreas(actor,"device.read",park);
        return store.db.queryForList("SELECT id AS areaId,park_id AS parkId,parent_area_id AS parentAreaId,area_type AS areaType,area_code AS areaCode,name,status,version FROM area WHERE park_id=? ORDER BY area_code",park).stream()
                .filter(a->visible.contains(a.get("areaId"))&&(parent==null||Objects.equals(parent,a.get("parentAreaId"))))
                .peek(a->{if(a.get("parentAreaId")!=null&&!visible.contains(a.get("parentAreaId"))) a.put("parentAreaId",null);}).toList();
    }
    public JsonNode createArea(AuthorizationContext actor,String key,CreateArea c) {
        if(c.areaType()==AreaType.BUILDING&&c.parentAreaId()!=null||c.areaType()!=AreaType.BUILDING&&c.parentAreaId()==null)throw ApiProblem.invalid("楼栋直属园区，楼层和区域必须有上级");
        if(c.parentAreaId()!=null){var parent=area(c.parentAreaId());if(!parent.get("status").equals("ACTIVE")||c.areaType()==AreaType.FLOOR&&!parent.get("area_type").equals("BUILDING")||c.areaType()==AreaType.AREA&&!Set.of("FLOOR","AREA").contains(parent.get("area_type")))throw ApiProblem.invalid("上级区域层级无效或已停用");}
        String park;
        if(c.parentAreaId()==null) {
            park="CAMPUS-A";
            if(actor.require("device.manage").stream().noneMatch(g->g.scopeType().equals("PARK")&&park.equals(g.parkId()))) throw ApiProblem.forbidden();
        } else { var parent=area(c.parentAreaId()); park=str(parent,"park_id"); requireArea(actor,"device.manage",park,c.parentAreaId()); }
        return write(actor,key,park,"AreaCreated",c,()->{
            String id=id(); store.db.update("INSERT INTO area(id,park_id,parent_area_id,area_type,area_code,name) VALUES(?,?,?,?,?,?)",id,park,c.parentAreaId(),c.areaType().name(),c.areaCode(),c.name());
            events.append("AreaCreated",id,park,actor,Map.of("areaId",id)); return Map.of("areaId",id,"version",0);
        });
    }
    public JsonNode updateArea(AuthorizationContext actor,String key,String id,UpdateArea c) {
        var existing=area(id); String park=str(existing,"park_id"); requireArea(actor,"device.manage",park,id);
        if(c.parentAreaId()!=null) { var parent=area(c.parentAreaId()); if(!park.equals(parent.get("park_id"))) throw ApiProblem.invalid("不能跨园区移动区域"); requireArea(actor,"device.manage",park,c.parentAreaId()); }
        return write(actor,key,park,"AreaUpdated",Map.of("id",id,"body",c),()->{
            version(area(id),c.expectedVersion());
            if(c.parentAreaId()!=null&&descendants(park,List.of(id)).contains(c.parentAreaId())) throw ApiProblem.invalid("区域树不能成环");
            store.db.update("UPDATE area SET parent_area_id=COALESCE(?,parent_area_id),name=COALESCE(?,name),status=COALESCE(?,status),version=version+1 WHERE id=?",c.parentAreaId(),c.name(),c.status(),id);
            events.append("AreaUpdated",id,park,actor,Map.of("areaId",id)); return Map.of("areaId",id,"version",c.expectedVersion()+1);
        });
    }
    public Page<Map<String,Object>> devices(AuthorizationContext actor,String type,String areaId,String areaScope,String status,String connectivity,String mode,String freshness,String keyword,String pointId,int page,int size) {
        checkPage(page,size); Set<String> visible=visibleDeviceIds(actor,"device.read");
        Set<String> locations=null;
        if(areaId!=null) { var a=area(areaId); requireArea(actor,"device.read",str(a,"park_id"),areaId); locations=areaScope.equals("EXACT")?Set.of(areaId):descendants(str(a,"park_id"),List.of(areaId)); }
        final Set<String> selected=locations;
        Set<String> bound=pointId==null?null:store.db.queryForList("SELECT ipc_device_id FROM monitoring_point_binding WHERE monitoring_point_id=? AND valid_to IS NULL",String.class,pointId).stream().collect(Collectors.toSet());
        if(pointId!=null) point(actor,"device.read",pointId);
        var items=store.db.queryForList("SELECT d.id FROM device d WHERE NOT d.legacy_only AND d.device_type IS NOT NULL ORDER BY d.created_at DESC,d.id DESC").stream()
                .map(d->str(d,"id")).filter(visible::contains).map(this::summary)
                .filter(d->type==null||type.equals(d.get("deviceType"))).filter(d->selected==null||selected.contains(d.get("areaId")))
                .filter(d->status==null||status.equals(d.get("assetStatus"))).filter(d->connectivity==null||connectivity.equals(d.get("connectivityStatus")))
                .filter(d->mode==null||mode.equals(d.get("operatingMode"))).filter(d->freshness==null||freshness.equals(d.get("dataFreshness")))
                .filter(d->bound==null||bound.contains(d.get("deviceId")))
                .filter(d->keyword==null||(d.get("name")+" "+d.get("assetCode")).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))).toList();
        return page(items,page,size);
    }
    private Map<String,Object> summary(String id) {
        var result=store.db.queryForMap("""
            SELECT d.id AS deviceId,d.device_code AS assetCode,d.device_type AS deviceType,d.name,d.campus_id AS parkId,
             d.area_id AS areaId,a.name AS areaName,d.asset_status AS assetStatus,d.version,d.updated_at AS updatedAt,
             COALESCE(s.connectivity_status,'UNKNOWN') AS connectivityStatus,COALESCE(s.operating_mode,'UNKNOWN') AS operatingMode,
             COALESCE(s.data_freshness,'NO_DATA') AS dataFreshness,COALESCE(s.version,0) AS stateVersion,
             s.last_heartbeat_received_at AS lastHeartbeatReceivedAt,s.last_business_data_received_at AS lastBusinessDataReceivedAt
            FROM device d JOIN area a ON a.id=d.area_id LEFT JOIN device_runtime_state s ON s.device_id=d.id WHERE d.id=?
            """,id);
        var port=health.getIfAvailable(); result.put("healthStatus",port==null?"UNKNOWN":port.deviceHealth(Set.of(id)).getOrDefault(id,"UNKNOWN")); return Views.row(result);
    }
    public Map<String,Object> detail(AuthorizationContext actor,String id) {
        requireVisibleDevices(actor,"device.read",List.of(id)); Map<String,Object> result=summary(id); var device=row(id);
        result.put("manufacturer",device.get("manufacturer")); result.put("model",device.get("model"));
        result.put("serialNumber",actor.permissions().contains("device.manage")?device.get("serial_number"):null);
        result.put("managementIp",device.get("management_ip")); result.put("criticality",device.get("criticality"));
        result.put("capabilities",parse(Objects.toString(device.get("capabilities_json"),"{}")));
        var topology=topology(actor,id,null,Instant.now(),1);
        @SuppressWarnings("unchecked") var relations=(List<Map<String,Object>>)topology.get("relations");
        result.put("networkDependencies",relations.stream().filter(r->r.get("relationType").equals("NETWORK")).toList());
        result.put("powerDependencies",relations.stream().filter(r->r.get("relationType").equals("POWER")).toList());
        result.put("videoChannels",relations.stream().filter(r->r.get("relationType").equals("VIDEO")).toList());
        result.put("monitoringPointBindings",bindings(actor,id,null)); result.put("completeness",topology.get("completeness")); return result;
    }
    public JsonNode createDevice(AuthorizationContext actor,String key,CreateDevice c) {
        var a=area(c.areaId()); String park=str(a,"park_id"); requireArea(actor,"device.manage",park,c.areaId());
        return write(actor,key,park,"DeviceRegistered",c,()->{
            if(!area(c.areaId()).get("status").equals("ACTIVE")) throw ApiProblem.invalid("安装区域已停用");
            String id=id(),secret=secret(); String encoded=org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(secret);
            var ancestors=legacyParents(c.areaId());
            store.db.update("""
             INSERT INTO device(id,campus_id,device_code,type,device_type,name,building_id,floor_id,area_id,password_hash,device_secret_hash,
               manufacturer,model,serial_number,management_ip,criticality,capabilities_json)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
             """,id,park,c.assetCode(),c.deviceType()==DeviceType.IPC?"CAMERA":c.deviceType().name(),c.deviceType().name(),c.name(),ancestors.get("building"),ancestors.get("floor"),c.areaId(),encoded,encoded,
                    c.manufacturer(),c.model(),c.serialNumber(),c.managementIp(),c.criticality()==null?"NORMAL":c.criticality(),serialize(c.capabilities()==null?Map.of():c.capabilities()));
            store.db.update("INSERT INTO device_runtime_state(device_id) VALUES(?)",id);
            store.db.update("INSERT INTO device_state_snapshot(device_id) VALUES(?)",id);
            events.append("DeviceRegistered",id,park,actor,Map.of("deviceId",id,"deviceType",c.deviceType()));
            return Map.of("device",summary(id),"deviceSecret",secret);
        });
    }
    public JsonNode updateDevice(AuthorizationContext actor,String key,String id,UpdateDevice c) {
        var before=requireVisibleDevices(actor,"device.manage",List.of(id)).getFirst();
        if(c.areaId()!=null) { if(!before.parkId().equals(area(c.areaId()).get("park_id"))) throw ApiProblem.invalid("不能跨园区迁移设备"); requireArea(actor,"device.manage",before.parkId(),c.areaId()); }
        return write(actor,key,before.parkId(),"DeviceUpdated",Map.of("id",id,"body",c),()->{
            var d=row(id); version(d,c.expectedVersion());
            if(d.get("asset_status").equals("RETIRED")) throw ApiProblem.conflict("退役设备不能重新启用或修改");
            if(c.assetStatus()==AssetStatus.REGISTERED&&!d.get("asset_status").equals("REGISTERED"))throw ApiProblem.invalid("已投用资产不能退回待投用状态");
            if(c.areaId()!=null){if(!area(c.areaId()).get("status").equals("ACTIVE"))throw ApiProblem.invalid("目标区域已停用");var parents=legacyParents(c.areaId());store.db.update("UPDATE device SET building_id=?,floor_id=? WHERE id=?",parents.get("building"),parents.get("floor"),id);}
            if(c.capabilities()!=null&&!Set.of("poeInput","poeOutput").containsAll(c.capabilities().keySet())) throw ApiProblem.invalid("设备能力无效");
            if(c.capabilities()!=null&&c.assetStatus()!=AssetStatus.RETIRED){
                if(!Boolean.TRUE.equals(c.capabilities().get("poeOutput"))&&Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM power_dependency WHERE poe_switch_id=? AND valid_to IS NULL",Boolean.class,id)))throw ApiProblem.conflict("请先结束当前 PoE 供电关系，再移除输出能力");
                if(!Boolean.TRUE.equals(c.capabilities().get("poeInput"))&&Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM power_dependency WHERE ipc_device_id=? AND valid_to IS NULL",Boolean.class,id)))throw ApiProblem.conflict("请先结束当前 PoE 供电关系，再移除受电能力");
            }
            if(c.assetStatus()==AssetStatus.RETIRED) endDeviceRelations(id,actor.userId());
            if(c.assetStatus()==AssetStatus.OUT_OF_SERVICE||c.assetStatus()==AssetStatus.RETIRED){
                long epoch=((Number)d.get("session_epoch")).longValue()+1;
                store.db.update("UPDATE device_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason='ASSET_DISABLED' WHERE device_id=? AND status<>'REVOKED'",id);
                store.db.update("UPDATE device SET session_epoch=?,active_session_id=NULL WHERE id=?",epoch,id);
                store.db.update("INSERT INTO broker_disconnect_outbox(device_id,expected_session_epoch) VALUES(?,?)",id,epoch);
            }
            store.db.update("""
             UPDATE device SET name=COALESCE(?,name),area_id=COALESCE(?,area_id),manufacturer=COALESCE(?,manufacturer),model=COALESCE(?,model),
              serial_number=COALESCE(?,serial_number),management_ip=COALESCE(?,management_ip),criticality=COALESCE(?,criticality),
              capabilities_json=COALESCE(?,capabilities_json),asset_status=COALESCE(?,asset_status),enabled=CASE WHEN ? IN ('OUT_OF_SERVICE','RETIRED') THEN FALSE WHEN ?='IN_SERVICE' THEN TRUE ELSE enabled END,
              version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
             """,c.name(),c.areaId(),c.manufacturer(),c.model(),c.serialNumber(),c.managementIp(),c.criticality(),c.capabilities()==null?null:serialize(c.capabilities()),
                    c.assetStatus()==null?null:c.assetStatus().name(),c.assetStatus()==null?null:c.assetStatus().name(),c.assetStatus()==null?null:c.assetStatus().name(),id);
            events.append("DeviceUpdated",id,before.parkId(),actor,Map.of("deviceId",id,"version",c.expectedVersion()+1)); return summary(id);
        });
    }
    private Map<String,String> legacyParents(String id) {
        Map<String,String> result=new HashMap<>(); String next=id; Set<String> seen=new HashSet<>();
        while(next!=null&&seen.add(next)) {
            var a=area(next);String kind=a.get("area_type").toString().toLowerCase(Locale.ROOT);
            if(Set.of("building","floor").contains(kind)){
                var mapping=store.db.queryForList("SELECT legacy_id FROM legacy_location_mapping WHERE legacy_table=? AND area_id=?",String.class,kind,next);
                if(!mapping.isEmpty())result.put(kind,mapping.getFirst());
            }
            next=(String)a.get("parent_area_id");
        }
        return result;
    }
    public Map<String,Object> overview(AuthorizationContext actor) {
        var rows=visibleDeviceIds(actor,"device.read").stream().map(this::summary).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",rows.size());
        for(String state:List.of("ONLINE","OFFLINE","UNKNOWN"))result.put(state.toLowerCase(Locale.ROOT),rows.stream().filter(r->state.equals(r.get("connectivityStatus"))).count());
        result.put("abnormal",rows.stream().filter(r->Set.of("FAULT","WARNING").contains(r.get("healthStatus"))).count());
        result.put("stale",rows.stream().filter(r->"STALE".equals(r.get("dataFreshness"))).count());return result;
    }
    public Map<String,Object> events(AuthorizationContext actor,String deviceId,String type,String status,Instant from,Instant to,int page,int size) {
        if(type!=null&&!Set.of("STATUS_OBSERVATION","DEVICE_FAULT","DEVICE_RECOVERED").contains(type))throw ApiProblem.invalid("事件类型无效；心跳不作为长期业务事件保存");
        if(status!=null&&!Set.of("APPLIED","IGNORED_STALE","DUPLICATE").contains(status))throw ApiProblem.invalid("处理状态无效");
        if(from!=null&&to!=null&&!from.isBefore(to))throw ApiProblem.invalid("时间范围无效");
        Set<String> visible=new HashSet<>(visibleDeviceIds(actor,"event.read"));visible.retainAll(visibleDeviceIds(actor,"device.read"));
        var rows=store.db.queryForList("SELECT event_id AS eventId,device_id AS deviceId,event_type AS eventType,stream_key AS streamKey,sequence_no AS sequence,session_epoch AS sessionEpoch,occurred_at AS occurredAt,received_at AS receivedAt,source,processing_status AS processingStatus,trace_id AS traceId FROM device_event WHERE schema_version='1' ORDER BY received_at DESC,id DESC").stream()
            .filter(r->visible.contains(r.get("deviceId"))&&(deviceId==null||deviceId.equals(r.get("deviceId"))))
            .filter(r->type==null||type.equals(r.get("eventType"))).filter(r->status==null||status.equals(r.get("processingStatus")))
            .filter(r->from==null||!DbTime.instant(r.get("receivedAt")).isBefore(from)).filter(r->to==null||DbTime.instant(r.get("receivedAt")).isBefore(to)).map(Views::row).toList();
        return Views.page(rows,page,size);
    }
    public Map<String,Object> event(AuthorizationContext actor,String id) {
        var rows=store.db.queryForList("SELECT event_id AS eventId,device_id AS deviceId,event_type AS eventType,stream_key AS streamKey,sequence_no AS sequence,session_epoch AS sessionEpoch,occurred_at AS occurredAt,received_at AS receivedAt,source,processing_status AS processingStatus,trace_id AS traceId,payload_json AS payload FROM device_event WHERE event_id=? AND schema_version='1'",id);
        if(rows.isEmpty())throw ApiProblem.missing();if(rows.size()>1)throw new ApiProblem(409,"AMBIGUOUS_EVENT_ID","事件标识跨来源重复，请按设备历史核查");var result=Views.row(rows.getFirst());String device=result.get("deviceId").toString();
        requireVisibleDevices(actor,"event.read",List.of(device));requireVisibleDevices(actor,"device.read",List.of(device));result.put("payload",parse(result.get("payload").toString()));return result;
    }
    @Override public boolean visibleEvidence(AuthorizationContext actor,String id,Collection<String> targets){
        var ids=store.db.queryForList("SELECT device_id FROM device_event WHERE event_id=? AND schema_version='1'",String.class,id);
        if(ids.isEmpty()||!new HashSet<>(targets).containsAll(ids))return false;
        try{requireVisibleDevices(actor,"event.read",ids);return true;}catch(ApiProblem hidden){return false;}
    }
    private void endDeviceRelations(String id,String actor) {
        store.db.update("UPDATE monitoring_point SET version=version+1 WHERE monitoring_point_id IN(SELECT monitoring_point_id FROM monitoring_point_binding WHERE ipc_device_id=? AND valid_to IS NULL)",id);
        store.db.update("UPDATE monitoring_point_binding SET valid_to=CURRENT_TIMESTAMP(3),ended_by=?,version=version+1 WHERE ipc_device_id=? AND valid_to IS NULL",actor,id);
        store.db.update("UPDATE network_dependency SET valid_to=CURRENT_TIMESTAMP(3),version=version+1 WHERE (upstream_switch_id=? OR downstream_device_id=?) AND valid_to IS NULL",id,id);
        store.db.update("UPDATE power_dependency SET valid_to=CURRENT_TIMESTAMP(3),version=version+1 WHERE (poe_switch_id=? OR ipc_device_id=?) AND valid_to IS NULL",id,id);
        store.db.update("UPDATE video_channel_association SET valid_to=CURRENT_TIMESTAMP(3),version=version+1 WHERE (nvr_device_id=? OR ipc_device_id=?) AND valid_to IS NULL",id,id);
    }
    public JsonNode createPoint(AuthorizationContext actor,String key,CreatePoint c) {
        String park=str(area(c.areaId()),"park_id"); requireArea(actor,"device.manage",park,c.areaId());
        if(!area(c.areaId()).get("status").equals("ACTIVE"))throw ApiProblem.invalid("区域已停用");
        return write(actor,key,park,"MonitoringPointCreated",c,()->{
            String id=id(); store.db.update("INSERT INTO monitoring_point(monitoring_point_id,park_id,area_id,point_code,name,description) VALUES(?,?,?,?,?,?)",id,park,c.areaId(),c.pointCode(),c.name(),c.description());
            events.append("MonitoringPointCreated",id,park,actor,Map.of("monitoringPointId",id)); return Map.of("monitoringPointId",id,"version",0);
        });
    }
    private Map<String,Object> point(AuthorizationContext actor,String permission,String id) {
        var rows=store.db.queryForList("SELECT * FROM monitoring_point WHERE monitoring_point_id=?",id); if(rows.isEmpty()) throw ApiProblem.missing();
        var p=rows.getFirst();if(!pointVisible(actor,permission,p))throw ApiProblem.missing();return p;
    }
    private boolean pointVisible(AuthorizationContext actor,String permission,Map<String,Object> p){
        String park=Objects.toString(p.getOrDefault("park_id",p.get("parkId"))),area=Objects.toString(p.getOrDefault("area_id",p.get("areaId")));
        for(var grant:actor.require(permission))if(park.equals(grant.parkId())){
            if(grant.scopeType().equals("PARK")||grant.scopeType().equals("AREA_SUBTREE")&&descendants(park,grant.rootAreaIds()).contains(area))return true;
        }
        String id=Objects.toString(p.getOrDefault("monitoring_point_id",p.get("monitoringPointId")));
        var assigned=visibleDeviceIds(actor,permission);
        return store.db.queryForList("SELECT ipc_device_id FROM monitoring_point_binding WHERE monitoring_point_id=? AND valid_to IS NULL",String.class,id).stream().anyMatch(assigned::contains);
    }
    public JsonNode updatePoint(AuthorizationContext actor,String key,String id,UpdatePoint c) {
        var p=point(actor,"device.manage",id); String park=str(p,"park_id");
        if(c.areaId()!=null) { if(!park.equals(area(c.areaId()).get("park_id"))) throw ApiProblem.invalid("点位不能跨园区移动"); requireArea(actor,"device.manage",park,c.areaId()); }
        return write(actor,key,park,"MonitoringPointUpdated",Map.of("id",id,"body",c),()->{
            version(point(actor,"device.manage",id),c.expectedVersion());
            if("INACTIVE".equals(c.status())) store.db.update("UPDATE monitoring_point_binding SET valid_to=CURRENT_TIMESTAMP(3),ended_by=?,version=version+1 WHERE monitoring_point_id=? AND valid_to IS NULL",actor.userId(),id);
            store.db.update("UPDATE monitoring_point SET name=COALESCE(?,name),area_id=COALESCE(?,area_id),description=COALESCE(?,description),status=COALESCE(?,status),version=version+1 WHERE monitoring_point_id=?",c.name(),c.areaId(),c.description(),c.status(),id);
            events.append("MonitoringPointUpdated",id,park,actor,Map.of("monitoringPointId",id)); return Map.of("monitoringPointId",id,"version",c.expectedVersion()+1);
        });
    }
    public List<Map<String,Object>> points(AuthorizationContext actor,String park) {
        Set<String> areas=visibleAreas(actor,"device.read",park);
        return store.db.queryForList("SELECT monitoring_point_id AS monitoringPointId,park_id AS parkId,area_id AS areaId,point_code AS pointCode,name,description,status,version FROM monitoring_point WHERE park_id=? ORDER BY point_code",park).stream().filter(p->areas.contains(p.get("areaId"))&&pointVisible(actor,"device.read",p)).toList();
    }
    public JsonNode bind(AuthorizationContext actor,String key,String pointId,BindPoint c) {
        var p=point(actor,"topology.manage",pointId); var d=requireVisibleDevices(actor,"topology.manage",List.of(c.ipcDeviceId())).getFirst(); String park=str(p,"park_id");
        if(!park.equals(d.parkId())||!d.deviceType().equals("IPC")) throw ApiProblem.invalid("点位只能绑定同园区 IPC");
        return write(actor,key,park,"MonitoringPointBound",Map.of("id",pointId,"body",c),()->{
            var current=point(actor,"topology.manage",pointId); version(current,c.expectedPointVersion()); version(row(d.deviceId()),c.expectedDeviceVersion()); active(d);
            if(!current.get("status").equals("ACTIVE")) throw ApiProblem.invalid("点位已停用");
            // Replacing the point's current IPC is explicit; an IPC already used by another point is rejected.
            if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM monitoring_point_binding WHERE ipc_device_id=? AND monitoring_point_id<>? AND valid_to IS NULL",Boolean.class,d.deviceId(),pointId))) throw ApiProblem.conflict("IPC 已绑定其他点位");
            java.time.LocalDateTime now=DbTime.sql(Instant.now());
            var replaced=store.db.queryForList("SELECT ipc_device_id FROM monitoring_point_binding WHERE monitoring_point_id=? AND valid_to IS NULL",String.class,pointId);
            requireVisibleDevices(actor,"topology.manage",replaced);
            for(String previous:replaced)if(!previous.equals(d.deviceId()))store.db.update("UPDATE device SET version=version+1 WHERE id=?",previous);
            store.db.update("UPDATE monitoring_point_binding SET valid_to=?,ended_by=?,version=version+1 WHERE monitoring_point_id=? AND valid_to IS NULL",now,actor.userId(),pointId);
            String id=id(); store.db.update("INSERT INTO monitoring_point_binding(binding_id,monitoring_point_id,ipc_device_id,valid_from,reason,created_by) VALUES(?,?,?,?,?,?)",id,pointId,d.deviceId(),now,c.reason(),actor.userId());
            store.db.update("UPDATE monitoring_point SET version=version+1 WHERE monitoring_point_id=?",pointId); store.db.update("UPDATE device SET version=version+1 WHERE id=?",d.deviceId());
            events.append("MonitoringPointBound",pointId,park,actor,Map.of("monitoringPointId",pointId,"ipcDeviceId",d.deviceId(),"bindingId",id)); return Map.of("bindingId",id,"version",0);
        });
    }
    public List<Map<String,Object>> bindings(AuthorizationContext actor,String deviceId,String pointId) {
        if(deviceId!=null) requireVisibleDevices(actor,"device.read",List.of(deviceId)); if(pointId!=null) point(actor,"device.read",pointId);
        Set<String> visible=visibleDeviceIds(actor,"device.read");
        return store.db.queryForList("SELECT binding_id AS bindingId,monitoring_point_id AS monitoringPointId,ipc_device_id AS ipcDeviceId,valid_from AS validFrom,valid_to AS validTo,reason,version FROM monitoring_point_binding ORDER BY valid_from,binding_id").stream()
                .filter(b->visible.contains(b.get("ipcDeviceId"))&&(deviceId==null||deviceId.equals(b.get("ipcDeviceId")))&&(pointId==null||pointId.equals(b.get("monitoringPointId"))))
                .filter(b->{try {point(actor,"device.read",str(b,"monitoringPointId"));return true;}catch(ApiProblem ignored){return false;}}).map(Views::row).toList();
    }
    public JsonNode network(AuthorizationContext actor,String key,Network c) {
        var devices=requireVisibleDevices(actor,"topology.manage",List.of(c.upstreamSwitchId(),c.downstreamDeviceId())); DeviceRef up=devices.getFirst(),down=devices.getLast();
        if(!up.parkId().equals(down.parkId())||!up.deviceType().equals("SWITCH")||up.deviceId().equals(down.deviceId())) throw ApiProblem.invalid("网络方向必须是同园区 SWITCH 到其他设备");
        return write(actor,key,up.parkId(),"NetworkDependencyCreated",c,()->{
            active(up); active(down); Map<String,String> parents=new HashMap<>();
            for(var edge:networkRelations(up.parkId(),Instant.now())) parents.put(str(edge,"downstreamDeviceId"),str(edge,"upstreamSwitchId"));
            String cursor=up.deviceId(); Set<String> seen=new HashSet<>();
            while(cursor!=null) { if(cursor.equals(down.deviceId())||!seen.add(cursor)) throw ApiProblem.invalid("网络依赖不能成环"); cursor=parents.get(cursor); }
            String id=id(); store.db.update("INSERT INTO network_dependency(dependency_id,park_id,upstream_switch_id,downstream_device_id,upstream_port_ref,downstream_port_ref,valid_from,change_reason) VALUES(?,?,?,?,?,?,?,?)",id,up.parkId(),up.deviceId(),down.deviceId(),c.upstreamPortRef(),c.downstreamPortRef(),DbTime.sql(Instant.now()),c.reason());
            events.append("NetworkDependencyCreated",id,up.parkId(),actor,Map.of("upstreamSwitchId",up.deviceId(),"downstreamDeviceId",down.deviceId())); return Map.of("dependencyId",id,"version",0);
        });
    }
    public JsonNode power(AuthorizationContext actor,String key,Power c) {
        var devices=requireVisibleDevices(actor,"topology.manage",List.of(c.poeSwitchId(),c.ipcDeviceId())); DeviceRef up=devices.getFirst(),down=devices.getLast();
        if(!up.parkId().equals(down.parkId())||!up.deviceType().equals("SWITCH")||!down.deviceType().equals("IPC")) throw ApiProblem.invalid("PoE 方向必须是同园区 SWITCH 到 IPC");
        return write(actor,key,up.parkId(),"PowerDependencyCreated",c,()->{
            active(up); active(down);
            if(!parse(Objects.toString(row(up.deviceId()).get("capabilities_json"),"{}")).path("poeOutput").asBoolean()||!parse(Objects.toString(row(down.deviceId()).get("capabilities_json"),"{}")).path("poeInput").asBoolean()) throw ApiProblem.invalid("两端必须具备对应 PoE 能力");
            String id=id(); store.db.update("INSERT INTO power_dependency(power_dependency_id,park_id,poe_switch_id,ipc_device_id,poe_port,valid_from,change_reason) VALUES(?,?,?,?,?,?,?)",id,up.parkId(),up.deviceId(),down.deviceId(),c.poePort(),DbTime.sql(Instant.now()),c.reason());
            events.append("PowerDependencyCreated",id,up.parkId(),actor,Map.of("poeSwitchId",up.deviceId(),"ipcDeviceId",down.deviceId())); return Map.of("powerDependencyId",id,"version",0);
        });
    }
    public JsonNode video(AuthorizationContext actor,String key,Video c) {
        var devices=requireVisibleDevices(actor,"topology.manage",List.of(c.nvrDeviceId(),c.ipcDeviceId())); DeviceRef up=devices.getFirst(),down=devices.getLast();
        if(!up.parkId().equals(down.parkId())||!up.deviceType().equals("NVR")||!down.deviceType().equals("IPC")||c.channelNo()<1) throw ApiProblem.invalid("录像关联必须是同园区 NVR 与 IPC，通道号为正整数");
        return write(actor,key,up.parkId(),"VideoChannelAssociated",c,()->{
            active(up); active(down); String id=id(); store.db.update("INSERT INTO video_channel_association(association_id,park_id,nvr_device_id,ipc_device_id,channel_no,valid_from,change_reason) VALUES(?,?,?,?,?,?,?)",id,up.parkId(),up.deviceId(),down.deviceId(),c.channelNo(),DbTime.sql(Instant.now()),c.reason());
            events.append("VideoChannelAssociated",id,up.parkId(),actor,Map.of("nvrDeviceId",up.deviceId(),"ipcDeviceId",down.deviceId())); return Map.of("associationId",id,"version",0);
        });
    }
    private record Relation(String table,String id,String source,String target,String kind) {}
    private static final Map<String,Relation> RELATIONS=Map.of(
            "network-dependencies",new Relation("network_dependency","dependency_id","upstream_switch_id","downstream_device_id","NETWORK"),
            "power-dependencies",new Relation("power_dependency","power_dependency_id","poe_switch_id","ipc_device_id","POWER"),
            "video-channel-associations",new Relation("video_channel_association","association_id","nvr_device_id","ipc_device_id","VIDEO"));
    public JsonNode end(AuthorizationContext actor,String key,String kind,String id,EndRelation c) {
        if(kind.equals("monitoring-point-bindings")) {
            var rows=store.db.queryForList("SELECT * FROM monitoring_point_binding WHERE binding_id=?",id); if(rows.isEmpty()) throw ApiProblem.missing(); var b=rows.getFirst();
            var p=point(actor,"topology.manage",str(b,"monitoring_point_id")); requireVisibleDevices(actor,"topology.manage",List.of(str(b,"ipc_device_id")));
            return write(actor,key,str(p,"park_id"),"MonitoringPointBindingEnded",Map.of("id",id,"body",c),()->{
                version(store.db.queryForMap("SELECT * FROM monitoring_point_binding WHERE binding_id=?",id),c.expectedVersion());
                if(store.db.update("UPDATE monitoring_point_binding SET valid_to=CURRENT_TIMESTAMP(3),ended_by=?,version=version+1 WHERE binding_id=? AND valid_to IS NULL",actor.userId(),id)!=1) throw ApiProblem.conflict("绑定已结束");
                store.db.update("UPDATE monitoring_point SET version=version+1 WHERE monitoring_point_id=?",b.get("monitoring_point_id"));
                store.db.update("UPDATE device SET version=version+1 WHERE id=?",b.get("ipc_device_id"));
                events.append("MonitoringPointBindingEnded",id,str(p,"park_id"),actor,Map.of("reason",c.reason())); return Map.of("bindingId",id,"version",c.expectedVersion()+1);
            });
        }
        Relation r=RELATIONS.get(kind); if(r==null) throw ApiProblem.missing();
        var rows=store.db.queryForList("SELECT * FROM "+r.table()+" WHERE "+r.id()+"=?",id); if(rows.isEmpty()) throw ApiProblem.missing(); var relation=rows.getFirst();
        requireVisibleDevices(actor,"topology.manage",List.of(str(relation,r.source()),str(relation,r.target())));
        return write(actor,key,str(relation,"park_id"),r.kind()+"RelationEnded",Map.of("id",id,"body",c),()->{
            version(store.db.queryForMap("SELECT * FROM "+r.table()+" WHERE "+r.id()+"=?",id),c.expectedVersion());
            if(store.db.update("UPDATE "+r.table()+" SET valid_to=CURRENT_TIMESTAMP(3),version=version+1 WHERE "+r.id()+"=? AND valid_to IS NULL",id)!=1) throw ApiProblem.conflict("关系已结束");
            events.append(r.kind()+"RelationEnded",id,str(relation,"park_id"),actor,Map.of("reason",c.reason())); return Map.of("relationId",id,"version",c.expectedVersion()+1);
        });
    }
    @Override public List<Map<String,Object>> networkRelations(String park,Instant asOf) {
        return store.db.queryForList("SELECT dependency_id AS relationId,upstream_switch_id AS upstreamSwitchId,downstream_device_id AS downstreamDeviceId,upstream_port_ref AS upstreamPortRef,valid_from AS validFrom,valid_to AS validTo,version FROM network_dependency WHERE park_id=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)",park,DbTime.sql(asOf),DbTime.sql(asOf));
    }
    public Map<String,Object> topology(AuthorizationContext actor,String deviceId,String pointId,Instant asOf,int depth) {
        if(depth<1||depth>3) throw ApiProblem.invalid("拓扑深度必须为 1–3");
        if(deviceId==null&&pointId==null) throw ApiProblem.invalid("必须提供设备或点位");
        String target=deviceId; Set<String> seeds=new HashSet<>();
        if(deviceId!=null) {requireVisibleDevices(actor,"topology.read",List.of(deviceId));seeds.add(deviceId);}
        if(pointId!=null) {
            point(actor,"topology.read",pointId); target=pointId;
            seeds.addAll(store.db.queryForList("SELECT ipc_device_id FROM monitoring_point_binding WHERE monitoring_point_id=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)",String.class,pointId,DbTime.sql(asOf),DbTime.sql(asOf)));
        }
        Set<String> visible=visibleDeviceIds(actor,"topology.read"); boolean incomplete=!visible.containsAll(seeds); seeds.retainAll(visible);
        List<Map<String,Object>> edges=new ArrayList<>(); Set<String> selected=new HashSet<>(seeds); Set<String> relationIds=new HashSet<>();
        for(int level=0;level<depth;level++) {
            Set<String> next=new HashSet<>(selected);
            for(var r:RELATIONS.values()) {
                for(var edge:store.db.queryForList("SELECT * FROM "+r.table()+" WHERE valid_from<=? AND (valid_to IS NULL OR valid_to>?)",DbTime.sql(asOf),DbTime.sql(asOf))) {
                    String from=str(edge,r.source()),to=str(edge,r.target());
                    if(!selected.contains(from)&&!selected.contains(to)) continue;
                    if(!visible.contains(from)||!visible.contains(to)) { incomplete=true; continue; }
                    if(next.size()>=100&&!next.contains(from)||next.size()>=100&&!next.contains(to)) { incomplete=true; continue; }
                    next.add(from);next.add(to);
                    if(relationIds.add(str(edge,r.id()))) {
                        Map<String,Object> view=new LinkedHashMap<>();view.put("relationId",edge.get(r.id()));view.put("relationType",r.kind());view.put("sourceDeviceId",from);view.put("targetDeviceId",to);
                        view.put("validFrom",edge.get("valid_from"));view.put("validTo",edge.get("valid_to"));view.put("source","ASSET_RELATION");view.put("version",edge.get("version"));
                        if(edge.containsKey("poe_port"))view.put("poePort",edge.get("poe_port"));if(edge.containsKey("channel_no"))view.put("channelNo",edge.get("channel_no"));if(edge.containsKey("upstream_port_ref"))view.put("upstreamPortRef",edge.get("upstream_port_ref")); edges.add(view);
                    }
                }
            }
            selected=next;
        }
        return Map.of("targetId",target,"asOf",asOf,"nodes",selected.stream().sorted().map(this::summary).toList(),"relations",edges.stream().map(Views::row).toList(),"completeness",incomplete?"INCOMPLETE":"COMPLETE","unknownRelations",incomplete||edges.isEmpty(),"configurationStatus",edges.isEmpty()?"UNCONFIGURED":"CONFIGURED_RELATIONS_AVAILABLE");
    }
    public Page<Map<String,Object>> history(AuthorizationContext actor,String id,String dimension,Instant from,Instant to,int page,int size) {
        requireVisibleDevices(actor,"device.read",List.of(id)); requireVisibleDevices(actor,"event.read",List.of(id)); checkPage(page,size);
        var rows=store.db.queryForList("SELECT history_id AS historyId,state_dimension AS stateDimension,old_value AS oldValue,new_value AS newValue,source_event_id AS sourceEventId,occurred_at AS occurredAt,received_at AS receivedAt,stream_key AS streamKey,stream_sequence AS streamSequence,state_version AS stateVersion FROM device_status_history WHERE device_id=? ORDER BY received_at DESC,history_id DESC",id).stream()
                .filter(h->dimension==null||dimension.equals(h.get("stateDimension"))).filter(h->from==null||!DbTime.instant(h.get("receivedAt")).isBefore(from))
                .filter(h->to==null||DbTime.instant(h.get("receivedAt")).isBefore(to)).toList();
        return page(rows,page,size);
    }
    private JsonNode write(AuthorizationContext actor,String key,String park,String type,Object command,Supplier<?> body) {
        return commands.execute(park,key,actor.userId(),type,command,()->{
            store.db.queryForObject("SELECT version FROM park WHERE park_id=? FOR UPDATE",Long.class,park);
            return body.get();
        });
    }
    private void active(DeviceRef d) { if(Set.of("OUT_OF_SERVICE","RETIRED").contains(device(d.deviceId()).assetStatus())) throw ApiProblem.conflict("停用或退役设备不能建立关系"); }
    private static void version(Map<String,Object> row,long expected) { if(((Number)row.get("version")).longValue()!=expected) throw ApiProblem.conflict("记录已变更，请刷新后重试"); }
    private static void checkPage(int page,int size) { if(page<1||size<1||size>100) throw ApiProblem.invalid("分页范围无效"); }
    private static <T> Page<T> page(List<T> rows,int page,int size) { int start=(int)Math.min((long)(page-1)*size,rows.size()); return new Page<>(rows.subList(start,Math.min(start+size,rows.size())),rows.size(),page,size); }
    private static String str(Map<String,Object> row,String key) { return Objects.toString(row.get(key),null); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String secret() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String serialize(Object object) { try{return json.writeValueAsString(object);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);} }
    private JsonNode parse(String value) { try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);} }
}
