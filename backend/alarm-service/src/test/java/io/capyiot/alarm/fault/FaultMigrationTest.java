package io.capyiot.alarm.fault;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class FaultMigrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.11").withDatabaseName("fault_migration");

  @Test
  void v6UpgradePreservesLegacyReservationsAndFencesAnInterruptedClose() throws Exception {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .target("6")
        .load()
        .migrate();
    try (var db =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var sql = db.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO"
              + " incident(incident_id,incident_no,park_id,title,status,priority,priority_reason,impact_snapshot_json,opened_at,created_by)"
              + " VALUES('incident-legacy','INC-LEGACY','PARK-A','历史故障','OPEN','P2','历史','{}',CURRENT_TIMESTAMP(3),'user-1')");
      sql.executeUpdate(
          "INSERT INTO incident_work_order_gate(incident_id,gate_status,version)"
              + " VALUES('incident-legacy','CLOSING',7)");
      sql.executeUpdate(
          "INSERT INTO"
              + " incident_work_order_reservation(reservation_id,incident_id,request_id,idempotency_key,actor_user_id,request_hash,operation_type,status)"
              + " VALUES('reservation-legacy','incident-legacy','request-legacy','key-legacy','user-1',REPEAT('a',64),'CREATE','RESERVED')");
      sql.executeUpdate(
          "INSERT INTO"
              + " fault_observation_cursor(device_id,stream_key,session_epoch,sequence_no,received_at)"
              + " VALUES('ipc-1','STATUS/DEVICE/device/VIDEO_SIGNAL',2,11,CURRENT_TIMESTAMP(3))");
    }
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var db =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var sql = db.createStatement()) {
      try (var row = sql.executeQuery("SELECT * FROM incident_work_order_reservation")) {
        assertTrue(row.next());
        assertEquals("reservation-legacy", row.getString("reservation_id"));
        assertEquals("RESERVED", row.getString("status"));
        assertNull(row.getString("operation_id"));
        assertEquals("a".repeat(64), row.getString("request_hash"));
      }
      try (var row = sql.executeQuery("SELECT * FROM incident_work_order_gate")) {
        assertTrue(row.next());
        assertEquals("CLOSING", row.getString("gate_status"));
        assertNotNull(row.getString("close_attempt_id"));
        assertEquals(8, row.getLong("version"));
        assertNotNull(row.getObject("closing_at"));
      }
      try (var row = sql.executeQuery("SELECT * FROM fault_applied_position")) {
        assertTrue(row.next());
        assertEquals(2, row.getLong("session_epoch"));
        assertEquals(11, row.getLong("sequence_no"));
      }
      sql.executeUpdate(
          "INSERT INTO"
              + " incident_work_order_reservation(reservation_id,incident_id,request_id,idempotency_key,actor_user_id,request_hash,operation_type,operation_id)"
              + " VALUES('new-1','incident-legacy','req-1','key-1','user-1',REPEAT('a',64),'CREATE','op-1')");
      assertThrows(
          SQLException.class,
          () ->
              sql.executeUpdate(
                  "INSERT INTO"
                      + " incident_work_order_reservation(reservation_id,incident_id,request_id,idempotency_key,actor_user_id,request_hash,operation_type,operation_id)"
                      + " VALUES('new-2','incident-legacy','req-2','key-2','user-1',REPEAT('a',64),'CREATE','op-1')"));
    }
  }
}
