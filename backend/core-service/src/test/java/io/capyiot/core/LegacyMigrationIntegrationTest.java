package io.capyiot.core;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Upgrade real historical rows instead of checking only an empty-schema installation. */
@Testcontainers
class LegacyMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.11").withDatabaseName("migration_test");
    @Test void preservesLegacyFactsAndDoesNotInventIncidentsOrAcceptanceRecords(){
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/device").target("7").load().migrate();
        String hash="preserved-legacy-password-hash";
        db.update("INSERT INTO device(id,campus_id,device_code,type,name,building_id,floor_id,area_id,password_hash) VALUES('legacy-camera','CAMPUS-A','OLD-CAM','CAMERA','旧摄像机','BLD-1','FL-1-1','AREA-1-1',?)",hash);
        db.update("INSERT INTO device(id,campus_id,device_code,type,name,building_id,floor_id,area_id,password_hash) VALUES('legacy-sensor','CAMPUS-A','OLD-SENSOR','SENSOR','历史传感器','BLD-1','FL-1-1','AREA-1-1',?)",hash);
        Flyway.configure().dataSource(source).locations("classpath:db/device").load().migrate();
        assertThat(db.queryForObject("SELECT device_type FROM device WHERE id='legacy-camera'",String.class)).isEqualTo("IPC");
        assertThat(db.queryForObject("SELECT device_secret_hash FROM device WHERE id='legacy-camera'",String.class)).isEqualTo(hash);
        assertThat(db.queryForObject("SELECT legacy_only FROM device WHERE id='legacy-sensor'",Boolean.class)).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM device",Integer.class)).isEqualTo(2);
    }
    @Test void preservesAlarmMappingWithoutGuessingRootCause(){
        // A separate container avoids putting different contexts in a shared migration history.
        try(var database=new MySQLContainer<>("mysql:8.4.11").withDatabaseName("fault_migration")){
            database.start();var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());var db=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/fault").target("5").load().migrate();
            db.update("INSERT INTO alarm(id,campus_id,device_id,device_name,building_id,alarm_type,severity,status,relation_kind,active_marker,first_seen_at,incident_at,last_seen_at) VALUES(41,'CAMPUS-A','legacy-camera','旧摄像机','BLD-1','DEVICE_OFFLINE','HIGH','ACTIVE','INDEPENDENT',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))");
            Flyway.configure().dataSource(source).locations("classpath:db/fault").load().migrate();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM alarm WHERE id=41",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT target_id FROM legacy_alarm_mapping WHERE legacy_alarm_id=41",String.class)).isEqualTo("LEGACY-ALARM-41");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM incident",Integer.class)).isZero();
        }
    }
    @Test void preservesOldWorkOrderNumbersAndCommentsWithoutFabricatedRepairOrAcceptance(){
        try(var database=new MySQLContainer<>("mysql:8.4.11").withDatabaseName("work_migration")){
            database.start();var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());var db=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/workflow").target("3").load().migrate();
            db.update("INSERT INTO work_order(id,campus_id,source_alarm_id,source_business_key,source_alarm_no,title,priority,status,created_by) VALUES(42,'CAMPUS-A',41,'CAMPUS-A:41','AL-41','历史处置','HIGH','CLOSED','operator')");
            db.update("INSERT INTO work_order_comment(order_id,order_version,actor,content) VALUES(42,1,'operator','保留原始说明')");
            Flyway.configure().dataSource(source).locations("classpath:db/workflow").load().migrate();
            assertThat(db.queryForObject("SELECT work_order_no FROM work_order WHERE id=42",String.class)).isEqualTo("WO-000042");
            assertThat(db.queryForObject("SELECT source_type FROM work_order WHERE id=42",String.class)).isEqualTo("LEGACY_ALARM");
            assertThat(db.queryForObject("SELECT content FROM work_order_comment WHERE order_id=42",String.class)).isEqualTo("保留原始说明");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_record",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM acceptance_record",Integer.class)).isZero();
        }
    }
}
