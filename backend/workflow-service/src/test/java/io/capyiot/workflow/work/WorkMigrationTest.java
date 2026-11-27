package io.capyiot.workflow.work;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class WorkMigrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.11").withDatabaseName("work_migration");

  @Test
  void completedReceiptsPreserveIdsHashesAgentBindingAndCommitVersion() throws Exception {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .target("5")
        .load()
        .migrate();
    try (var db =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var sql = db.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO"
              + " work_order(id,campus_id,source_business_key,title,priority,status,created_by,work_order_no,source_type,incident_id,version,last_reservation_id)"
              + " VALUES(42,'PARK-A','source-42','历史维修','P2','CLOSED','user-1','WO-000042','INCIDENT','incident-1',7,'reservation-old')");
      sql.executeUpdate(
          "INSERT INTO"
              + " work_order_device_item(item_id,work_order_id,device_id,asset_code_snapshot,device_name_snapshot,device_type_snapshot,device_role,issue_description)"
              + " VALUES('item-1',42,'ipc-1','IPC-1','摄像机','IPC','MAIN','问题')");
      sql.executeUpdate(
          "INSERT INTO"
              + " work_order_request(campus_id,idempotency_key,fingerprint,order_id,actor_user_id,command_type,request_hash,result_snapshot,reservation_id,agent_confirmation_id,agent_draft_id,agent_draft_revision,agent_request_hash)"
              + " VALUES('PARK-A','agent-confirm-1',REPEAT('a',64),42,'user-1','WorkOrderCreated',REPEAT('a',64),'{\"workOrderId\":\"42\",\"status\":\"OPEN\",\"version\":0}',"
              + " 'reservation-old','confirm-1','draft-1',3,REPEAT('b',64))");
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
      try (var row = sql.executeQuery("SELECT * FROM work_order_operation")) {
        assertTrue(row.next());
        assertEquals("COMMITTED", row.getString("state"));
        assertEquals(42, row.getLong("work_order_id"));
        assertEquals(0, row.getLong("work_order_version"));
        assertEquals("a".repeat(64), row.getString("protocol_hash"));
        assertEquals("reservation-old", row.getString("reservation_id"));
        assertEquals("confirm-1", row.getString("agent_confirmation_id"));
        assertEquals(3, row.getLong("agent_draft_revision"));
        assertEquals("b".repeat(64), row.getString("agent_request_hash"));
        assertFalse(row.next());
      }
      try (var row =
          sql.executeQuery(
              "SELECT COUNT(*) FROM work_order_operation_device WHERE device_id='ipc-1'")) {
        assertTrue(row.next());
        assertEquals(1, row.getInt(1));
      }
      try (var row = sql.executeQuery("SELECT version FROM work_order WHERE id=42")) {
        assertTrue(row.next());
        assertEquals(7, row.getLong(1));
      }
      assertThrows(
          SQLException.class,
          () ->
              sql.executeUpdate(
                  "INSERT INTO"
                      + " work_order_operation(operation_id,campus_id,idempotency_key,actor_user_id,operation_type,command_type,request_hash,protocol_hash)"
                      + " VALUES('duplicate','PARK-A','agent-confirm-1','user-1','CREATE','WorkOrderCreated',REPEAT('a',64),REPEAT('a',64))"));
    }
  }
}
