package io.capyiot.device.video;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Source only until MS-09: upgrade an installed V14 schema with sent and pending historical facts. */
@Testcontainers
class FaultPositionMigrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.11").withDatabaseName("device_migration");
    @Test void upgradeKeepsLatestRequiredFactsIncludingUnpublishedAndDeadOutboxRows() throws Exception {
        var base=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").target("14").load();base.migrate();
        try(var db=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())) {
            append(db,"connected","DeviceConnectionRestored","SENT","{\"stateVersion\":1}","device");
            append(db,"lost","DeviceConnectionLost","PENDING","{\"stateVersion\":4}","device");
            append(db,"observation-old","DeviceObservationAccepted","SENT","{\"streamKey\":\"STATUS/DEVICE/device/VIDEO_SIGNAL\",\"sessionEpoch\":1,\"sequence\":10}","device");
            append(db,"observation-new","DeviceObservationAccepted","DEAD","{\"streamKey\":\"STATUS/DEVICE/device/VIDEO_SIGNAL\",\"sessionEpoch\":2,\"sequence\":1}","device");
            append(db,"runtime-only","DeviceRuntimeStateChanged","PENDING","{\"stateVersion\":99}","device");
            append(db,"wrong-context","DeviceConnectionLost","PENDING","{\"stateVersion\":100}","fault");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        try(var db=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var statement=db.createStatement();var rows=statement.executeQuery("SELECT * FROM device_fault_position ORDER BY stream_key")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getString("stream_key")).isEqualTo("CONNECTIVITY");assertThat(rows.getString("event_id")).isEqualTo("lost");assertThat(rows.getLong("sequence_no")).isEqualTo(4);
            assertThat(rows.next()).isTrue();assertThat(rows.getString("event_id")).isEqualTo("observation-new");assertThat(rows.getLong("session_epoch")).isEqualTo(2);assertThat(rows.getLong("sequence_no")).isEqualTo(1);assertThat(rows.next()).isFalse();
        }
    }
    void append(Connection db,String event,String type,String status,String body,String context) throws SQLException {
        try(var statement=db.prepareStatement("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json,status) VALUES('device','ipc-1',?,?,'capyiot.domain.device.v1','ipc-1',?,?)")) {
            statement.setString(1,type);statement.setString(2,event);statement.setString(3,"{\"sourceContext\":\""+context+"\",\"body\":"+body+"}");statement.setString(4,status);statement.executeUpdate();
        }
    }
}
