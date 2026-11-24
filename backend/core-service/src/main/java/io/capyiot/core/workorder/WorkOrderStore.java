package io.capyiot.core.workorder;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="capyiot.workorders.enabled",havingValue="true")
final class WorkOrderStore {
    private final HikariDataSource source;final JdbcTemplate db;final TransactionTemplate tx;
    WorkOrderStore(@Value("${WORKFLOW_DB_URL:jdbc:mysql://localhost:3306/workflow_db?connectionTimeZone=UTC}") String url,
                   @Value("${WORKFLOW_DB_USER:workflow_app}") String user,@Value("${WORKFLOW_DB_PASSWORD}") String password){
        source=new HikariDataSource();source.setPoolName("workorder-module");source.setJdbcUrl(url);source.setUsername(user);source.setPassword(password);source.setMaximumPoolSize(5);
        Flyway.configure().dataSource(source).locations("classpath:db/workflow").load().migrate();db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @PreDestroy void close(){source.close();}
    @org.springframework.context.annotation.Bean
    @ConditionalOnProperty(name="capyiot.domain-runtime.enabled",havingValue="true")
    io.capyiot.outbox.OutboxRelay workOrderOutbox(org.springframework.kafka.core.KafkaTemplate<String,String> kafka,com.fasterxml.jackson.databind.ObjectMapper json) {
        return new io.capyiot.outbox.OutboxRelay(db,kafka,json,tx.getTransactionManager(),true,20);
    }
}
