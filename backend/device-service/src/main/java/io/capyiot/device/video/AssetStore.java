package io.capyiot.device.video;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
/** Only the owned primary datasource; Flyway runs once at application startup. */
@Component
final class AssetStore {
    final JdbcTemplate db;final TransactionTemplate tx;
    AssetStore(JdbcTemplate db,TransactionTemplate tx){this.db=db;this.tx=tx;}
}
