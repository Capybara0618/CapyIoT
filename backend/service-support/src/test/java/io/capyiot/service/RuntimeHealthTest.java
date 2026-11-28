package io.capyiot.service;

import io.capyiot.service.runtime.RuntimeAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.actuate.health.HealthIndicator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuntimeHealthTest {
    @Test void readinessUsesOnlyOwnedDatabaseAndRecovers() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false, true);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RuntimeAutoConfiguration.class))
            .withBean(DataSource.class, () -> source).run(context -> {
                var health = context.getBean("localDatabaseHealthIndicator", HealthIndicator.class);
                assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
                assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            });
    }
}
