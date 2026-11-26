package io.capyiot.core.platform;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** In-process domain tests only. These classes/resources never enter the production Core jar. */
@Configuration
@ConditionalOnProperty(name="capyiot.platform.test-fixture",havingValue="true")
class LocalPlatformFixture {
    @Bean(destroyMethod="close") HikariDataSource fixtureSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password){
        var source=new HikariDataSource();source.setJdbcUrl(url);source.setUsername(user);source.setPassword(password);source.setMaximumPoolSize(5);
        Flyway.configure().dataSource(source).locations("classpath:db/platform").load().migrate();return source;
    }
    @Bean JdbcTemplate fixtureJdbc(DataSource source){return new JdbcTemplate(source);}
    @Bean DataSourceTransactionManager fixtureTransactions(DataSource source){return new DataSourceTransactionManager(source);}
    @Bean TransactionTemplate fixtureTemplate(DataSourceTransactionManager manager){return new TransactionTemplate(manager);}
}
