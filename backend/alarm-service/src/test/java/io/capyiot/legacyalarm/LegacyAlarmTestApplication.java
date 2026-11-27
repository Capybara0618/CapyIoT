package io.capyiot.legacyalarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.capyiot.outbox.OutboxConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableScheduling
@Import(OutboxConfiguration.class)
public class LegacyAlarmTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(LegacyAlarmTestApplication.class, args);
    }
}
