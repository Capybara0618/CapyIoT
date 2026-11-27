package io.capyiot.alarm.fault;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class FaultStore {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final TransactionTemplate read;

  FaultStore(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = new TransactionTemplate(tx.getTransactionManager());
    this.tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.read = new TransactionTemplate(tx.getTransactionManager());
    this.read.setReadOnly(true);
    this.read.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }
}
