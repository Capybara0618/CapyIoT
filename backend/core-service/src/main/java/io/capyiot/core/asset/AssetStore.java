package io.capyiot.core.asset;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Own schema and credentials; no other context receives this store. */
@Component
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
final class AssetStore {
    private final HikariDataSource source;
    final JdbcTemplate db;
    final TransactionTemplate tx;
    AssetStore(@Value("${DEVICE_DB_URL:jdbc:mysql://localhost:3306/device_db?connectionTimeZone=UTC}") String url,
               @Value("${DEVICE_DB_USER:device_app}") String user,@Value("${DEVICE_DB_PASSWORD}") String password) {
        source=new HikariDataSource(); source.setPoolName("asset-module"); source.setJdbcUrl(url); source.setUsername(user); source.setPassword(password); source.setMaximumPoolSize(5);
        Flyway.configure().dataSource(source).locations("classpath:db/device").load().migrate();
        db=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @PreDestroy void close() { source.close(); }
}
