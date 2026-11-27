package io.capyiot.legacyworkflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.capyiot.outbox.OutboxConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(OutboxConfiguration.class)
public class LegacyWorkflowTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(LegacyWorkflowTestApplication.class, args);
    }
}
