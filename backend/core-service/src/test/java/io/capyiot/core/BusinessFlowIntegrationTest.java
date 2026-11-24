package io.capyiot.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.capyiot.core.asset.*;
import io.capyiot.core.fault.*;
import io.capyiot.core.platform.*;
import io.capyiot.core.workorder.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
class BusinessFlowIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.11").withDatabaseName("platform_test").withUsername("root").withPassword("test");
    static final String HASH=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
        StringBuilder sql=new StringBuilder();for(String schema:List.of("device","fault","workflow"))sql.append("CREATE DATABASE IF NOT EXISTS ").append(schema).append("_test; CREATE USER IF NOT EXISTS '").append(schema).append("_test_app'@'%' IDENTIFIED BY 'test'; GRANT ALL ON ").append(schema).append("_test.* TO '").append(schema).append("_test_app'@'%';");
        var result=MYSQL.execInContainer("mysql","-uroot","-ptest","-e",sql.toString());if(result.getExitCode()!=0)throw new IllegalStateException(result.getStderr());
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"test");
        r.add("capyiot.foundation.service-key",()->"test-service-key-with-at-least-32-characters");r.add("capyiot.foundation.jwks-uri",()->"http://127.0.0.1:1/jwks");
        r.add("capyiot.foundation.bootstrap.admin-hash",()->HASH);r.add("capyiot.foundation.bootstrap.operator-hash",()->HASH);
        r.add("capyiot.assets.enabled",()->true);r.add("capyiot.faults.enabled",()->true);r.add("capyiot.workorders.enabled",()->true);
        r.add("capyiot.device-key-file",()->System.getProperty("java.io.tmpdir")+"/capyiot-business-test-"+UUID.randomUUID()+".json");r.add("capyiot.device-keys-generate",()->true);
        for(var entry:Map.of("DEVICE","device","ALARM","fault","WORKFLOW","workflow").entrySet()){
            r.add(entry.getKey()+"_DB_URL",()->MYSQL.getJdbcUrl().replace("/platform_test","/"+entry.getValue()+"_test"));
            r.add(entry.getKey()+"_DB_USER",()->entry.getValue()+"_test_app");r.add(entry.getKey()+"_DB_PASSWORD",()->"test");
        }
    }
    @Autowired AssetService assets;@Autowired FaultService faults;@Autowired WorkOrderService orders;@Autowired ObjectMapper json;
    JdbcTemplate db;
    AuthorizationContext supervisor=actor("USER-SUPERVISOR","OPS_SUPERVISOR","PARK",null);
    AuthorizationContext operator=actor("USER-OPERATOR","OPS_OPERATOR","PARK",null);
    AuthorizationContext repairer=actor("USER-REPAIRER","REPAIR_TECH","ASSIGNED_TO_ME","REPAIRER");
    AuthorizationContext acceptor=actor("USER-ACCEPTOR","ACCEPTANCE_STAFF","ASSIGNED_TO_ME","ACCEPTOR");
    static AuthorizationContext actor(String id,String role,String scope,String relation){return new AuthorizationContext(id,id,id,1,"fixture-session",List.of(new AuthorizationContext.Grant("g-"+role,role,scope,"CAMPUS-A",List.of(),relation)));}
    String key(){return UUID.randomUUID().toString();}
    @BeforeEach void reset(){
        db=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root","test"));
        for(String table:List.of("work_order_request","work_order_device_item","repair_record","acceptance_record","work_order_history","work_order_comment","work_order","command_request","outbox_event")){
            if(!table.equals("command_request"))db.update("DELETE FROM workflow_test."+table);
        }
        for(String table:List.of("incident_work_order_projection","incident_work_order_reservation","incident_work_order_gate","incident_anomaly_link","correlation_candidate","incident","legacy_alarm_mapping","anomaly_evidence","anomaly","fault_observation_cursor","fault_device_observation","command_request","inbox_event","outbox_event"))db.update("DELETE FROM fault_test."+table);
        for(String table:List.of("broker_disconnect_outbox","monitoring_point_binding","network_dependency","power_dependency","video_channel_association","monitoring_point","device_event_receipt","device_stream_cursor","device_status_history","device_runtime_state","network_link","device_state_history","device_state_snapshot","device_event","device_session","device","command_request","outbox_event"))db.update("DELETE FROM device_test."+table);
    }
    String device(String code,AssetModels.DeviceType type){return assets.createDevice(supervisor,key(),new AssetModels.CreateDevice(code,type,code,"AREA-1-1",null,null,null,null,null,Map.of())).path("device").path("deviceId").asText();}
    JsonNode observation(String id,String eventType,long version){return json.valueToTree(Map.of("eventId",key(),"eventType",eventType,"aggregateId",id,"parkId","CAMPUS-A","occurredAt",Instant.now(),"body",Map.of("stateVersion",version,"receivedAt",Instant.now())));}
    String incident(String device){faults.observation(observation(device,"DeviceConnectionLost",1));String anomaly=db.queryForObject("SELECT anomaly_id FROM fault_test.anomaly WHERE device_id=? AND status='ACTIVE'",String.class,device);
        return faults.create(supervisor,key(),new FaultModels.Create("设备通信故障",List.of(anomaly),FaultModels.Priority.P3,"人工研判需维修")).path("incidentId").asText();}
    WorkOrderModels.Create create(String device,String incident){return new WorkOrderModels.Create(incident==null?WorkOrderModels.Source.MANUAL:WorkOrderModels.Source.INCIDENT,incident,List.of(new WorkOrderModels.DeviceItem(device,WorkOrderModels.DeviceRole.MAIN,"检查通信链路")),"维修通信链路","现场排查设备通信","NETWORK",incident==null?WorkOrderModels.Priority.P3:null,null,"设备通信恢复",null);}
    long version(long id){return db.queryForObject("SELECT version FROM workflow_test.work_order WHERE id=?",Long.class,id);}
    @Test void anomalyOccurrenceRecoveryAndNewOccurrenceKeepEvidenceAndIndependentIds(){
        String id=device("IPC-1",AssetModels.DeviceType.IPC);JsonNode lost=observation(id,"DeviceConnectionLost",1);faults.observation(lost);faults.observation(lost);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fault_test.anomaly",Integer.class));assertEquals(1L,db.queryForObject("SELECT observation_count FROM fault_test.anomaly",Long.class));
        faults.observation(observation(id,"DeviceConnectionRestored",3));faults.observation(observation(id,"DeviceConnectionLost",2));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fault_test.anomaly WHERE status='ACTIVE'",Integer.class));
        faults.observation(observation(id,"DeviceConnectionLost",4));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM fault_test.anomaly",Integer.class));
        assertNotNull(db.queryForObject("SELECT previous_anomaly_id FROM fault_test.anomaly WHERE status='ACTIVE'",String.class));
    }
    @Test void correlatedSwitchAndTwoIpcsCreatePendingIncidentWithoutConfirmingRootCause(){
        String sw=device("SW-1",AssetModels.DeviceType.SWITCH),ipc1=device("IPC-1",AssetModels.DeviceType.IPC),ipc2=device("IPC-2",AssetModels.DeviceType.IPC);
        assets.network(supervisor,key(),new AssetModels.Network(sw,ipc1,"p1",null,"安装"));assets.network(supervisor,key(),new AssetModels.Network(sw,ipc2,"p2",null,"安装"));
        for(String id:List.of(sw,ipc1,ipc2))faults.observation(observation(id,"DeviceConnectionLost",1));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fault_test.incident",Integer.class));
        assertEquals("PENDING_REVIEW",db.queryForObject("SELECT status FROM fault_test.incident",String.class));assertEquals("UNCONFIRMED",db.queryForObject("SELECT root_cause_status FROM fault_test.incident",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM workflow_test.work_order",Integer.class));
        faults.correlate("CAMPUS-A");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fault_test.incident",Integer.class));
    }
    @Test void fullManualRepairAndIndependentAcceptanceUseAllEightStateRules(){
        String device=device("IPC-1",AssetModels.DeviceType.IPC);long id=Long.parseLong(orders.create(operator,"create",key(),create(device,null)).path("workOrderId").asText());
        assertFalse(orders.create(operator,"create",key(),create(device,null)).path("created").asBoolean());
        orders.assign(supervisor,key(),id,new WorkOrderModels.Assign("USER-REPAIRER","USER-ACCEPTOR",0L,null),false);
        assertThrows(ApiProblem.class,()->orders.accept(operator,key(),id,new WorkOrderModels.Version(1L)));
        orders.accept(repairer,key(),id,new WorkOrderModels.Version(1L));orders.start(repairer,key(),id,new WorkOrderModels.Version(2L));
        orders.repair(repairer,key(),id,new WorkOrderModels.Repair("DIAGNOSIS","检查网络线缆",List.of(),3L));
        orders.submit(repairer,key(),id,new WorkOrderModels.Completion("已更换线缆",List.of(new WorkOrderModels.DeviceResult(device,"通信恢复")),List.of(),4L));
        assertThrows(ApiProblem.class,()->orders.acceptance(repairer,key(),id,new WorkOrderModels.Acceptance(WorkOrderModels.AcceptanceResult.PASS,"自验",List.of(new WorkOrderModels.Check("通信",true)),List.of(),5L)));
        orders.acceptance(acceptor,key(),id,new WorkOrderModels.Acceptance(WorkOrderModels.AcceptanceResult.RETURN,"还需检查交换机端口",List.of(new WorkOrderModels.Check("通信",false)),List.of(),5L));
        orders.start(repairer,key(),id,new WorkOrderModels.Version(6L));assertEquals(2,db.queryForObject("SELECT work_cycle_no FROM workflow_test.work_order WHERE id=?",Integer.class,id));
        orders.submit(repairer,key(),id,new WorkOrderModels.Completion("已复查端口",List.of(new WorkOrderModels.DeviceResult(device,"链路正常")),List.of(),7L));
        orders.acceptance(acceptor,"accept-final",id,new WorkOrderModels.Acceptance(WorkOrderModels.AcceptanceResult.PASS,"复核通过",List.of(new WorkOrderModels.Check("通信",true)),List.of(),8L));
        assertEquals("CLOSED",orders.detail(operator,id).get("status"));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM workflow_test.acceptance_record",Integer.class));
        assertThrows(ApiProblem.class,()->orders.reopen(supervisor,key(),key(),id,new WorkOrderModels.Reason(9L,"再次故障")));
    }
    @Test void incidentMustBeConfirmedAndReservationPreventsClosingBeforeWorkIsTerminal(){
        String device=device("IPC-1",AssetModels.DeviceType.IPC),incident=incident(device);var command=create(device,incident);
        assertThrows(ApiProblem.class,()->orders.create(operator,key(),key(),command));
        faults.review(supervisor,key(),incident,new FaultModels.Review(FaultModels.Decision.CONFIRM,"确认需处理",0L));
        long id=Long.parseLong(orders.create(operator,"incident-create",key(),command).path("workOrderId").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fault_test.incident_work_order_reservation WHERE status='COMMITTED'",Integer.class));
        faults.observation(observation(device,"DeviceConnectionRestored",2));
        long version=db.queryForObject("SELECT version FROM fault_test.incident WHERE incident_id=?",Long.class,incident);
        assertThrows(ApiProblem.class,()->faults.close(supervisor,key(),incident,new FaultModels.Close("REPAIRED","恢复核查",version)));
        orders.cancel(supervisor,key(),id,new WorkOrderModels.Reason(0L,"无需现场维修"));
        var closed=faults.close(supervisor,"close",incident,new FaultModels.Close("NO_REPAIR_REQUIRED","恢复证据与取消工单已复核",version));assertEquals("CLOSED",closed.path("status").asText());
        assertEquals("CLOSED",faults.close(supervisor,"close",incident,new FaultModels.Close("NO_REPAIR_REQUIRED","恢复证据与取消工单已复核",version)).path("status").asText());
        assertThrows(ApiProblem.class,()->orders.create(operator,key(),key(),command));
    }
    @Test void reservationWithoutKnownResultIsNeverReleasedByTimeout(){
        String device=device("IPC-1",AssetModels.DeviceType.IPC),incident=incident(device);faults.review(supervisor,key(),incident,new FaultModels.Review(FaultModels.Decision.CONFIRM,"确认",0L));
        var reservation=faults.reserve(operator,incident,"CREATE",key(),key(),"a".repeat(64));faults.markUncertain(reservation.reservationId());
        db.update("UPDATE fault_test.incident_work_order_reservation SET created_at=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(3))");faults.reconcile(incident);
        assertEquals("RECONCILIATION_REQUIRED",db.queryForObject("SELECT status FROM fault_test.incident_work_order_reservation WHERE reservation_id=?",String.class,reservation.reservationId()));
        faults.observation(observation(device,"DeviceConnectionRestored",2));assertThrows(ApiProblem.class,()->faults.close(supervisor,key(),incident,new FaultModels.Close("REPAIRED","核查",1L)));
    }
    @Test void assignmentRejectsSamePersonAndMissingRoleAndCancelIsTerminal(){
        String device=device("IPC-1",AssetModels.DeviceType.IPC);long id=Long.parseLong(orders.create(operator,key(),key(),create(device,null)).path("workOrderId").asText());
        assertThrows(ApiProblem.class,()->orders.assign(supervisor,key(),id,new WorkOrderModels.Assign("USER-REPAIRER","USER-REPAIRER",0L,null),false));
        assertThrows(ApiProblem.class,()->orders.assign(supervisor,key(),id,new WorkOrderModels.Assign("USER-OPERATOR","USER-ACCEPTOR",0L,null),false));
        orders.cancel(supervisor,"cancel",id,new WorkOrderModels.Reason(0L,"取消重复需求"));
        assertEquals("CANCELLED",orders.cancel(supervisor,"cancel",id,new WorkOrderModels.Reason(0L,"取消重复需求")).path("status").asText());
        assertThrows(ApiProblem.class,()->orders.assign(supervisor,key(),id,new WorkOrderModels.Assign("USER-REPAIRER","USER-ACCEPTOR",1L,null),false));
    }
    @Test void partialScopeCannotReadCompositeOrdersOrIncidents(){
        String visible=device("IPC-1",AssetModels.DeviceType.IPC);String hidden=assets.createDevice(supervisor,key(),new AssetModels.CreateDevice("IPC-2",AssetModels.DeviceType.IPC,"Hidden","AREA-1-2",null,null,null,null,null,null)).path("device").path("deviceId").asText();
        var command=new WorkOrderModels.Create(WorkOrderModels.Source.MANUAL,null,List.of(new WorkOrderModels.DeviceItem(visible,WorkOrderModels.DeviceRole.MAIN,"主设备"),new WorkOrderModels.DeviceItem(hidden,WorkOrderModels.DeviceRole.RELATED,"关联设备")),"多设备维修","检查两区域设备","NETWORK",WorkOrderModels.Priority.P3,null,"恢复",null);
        long id=Long.parseLong(orders.create(operator,key(),key(),command).path("workOrderId").asText());
        var scoped=new AuthorizationContext("scoped","scoped","scoped",1,"s",List.of(new AuthorizationContext.Grant("g","OPS_OPERATOR","AREA_SUBTREE","CAMPUS-A",List.of("AREA-1-1"),null)));
        assertEquals(404,assertThrows(ApiProblem.class,()->orders.detail(scoped,id)).status());assertEquals(0,((Number)orders.list(scoped,null,null,null,null,null,null,null,null,1,20).get("total")).intValue());
    }
    @Test void closingAndNewReservationCannotBothSucceedConcurrently()throws Exception{
        String device=device("IPC-RACE",AssetModels.DeviceType.IPC),incident=incident(device);faults.review(supervisor,key(),incident,new FaultModels.Review(FaultModels.Decision.CONFIRM,"确认",0L));faults.observation(observation(device,"DeviceConnectionRestored",2));
        long incidentVersion=db.queryForObject("SELECT version FROM fault_test.incident WHERE incident_id=?",Long.class,incident);
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            var reserve=workers.submit(()->{start.await();try{faults.reserve(supervisor,incident,"REOPEN",key(),key(),"b".repeat(64));return true;}catch(ApiProblem expected){return false;}});
            var close=workers.submit(()->{start.await();try{faults.close(supervisor,key(),incident,new FaultModels.Close("NO_REPAIR_REQUIRED","恢复复核",incidentVersion));return true;}catch(ApiProblem expected){return false;}});
            start.countDown();boolean reserved=reserve.get(10,TimeUnit.SECONDS),closed=close.get(10,TimeUnit.SECONDS);
            assertTrue(reserved||closed);assertFalse(reserved&&closed);
        }
    }
    @Test void repeatedConcurrentCreateProducesOneOrderAndOneReservation()throws Exception{
        String device=device("IPC-CREATE",AssetModels.DeviceType.IPC),incident=incident(device);faults.review(supervisor,key(),incident,new FaultModels.Review(FaultModels.Decision.CONFIRM,"确认",0L));var command=create(device,incident);
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            var requests=java.util.stream.IntStream.range(0,2).mapToObj(index->workers.submit(()->{start.await();return orders.create(operator,"concurrent-create",key(),command).path("workOrderId").asText();})).toList();
            start.countDown();assertEquals(requests.getFirst().get(10,TimeUnit.SECONDS),requests.getLast().get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM workflow_test.work_order",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fault_test.incident_work_order_reservation",Integer.class));
    }
}
