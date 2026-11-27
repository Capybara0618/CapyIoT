package io.capyiot.workflow.work;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capyiot.workorder-runtime.enabled", havingValue = "true")
class WorkRecovery {
  private final WorkOperations operations;

  WorkRecovery(WorkOperations operations) {
    this.operations = operations;
  }

  @Scheduled(fixedDelay = 5000)
  void tick() {
    try {
      RequestCalls.background(operations::recover);
    } catch (RuntimeException unavailable) {
      System.getLogger(getClass().getName())
          .log(
              System.Logger.Level.WARNING,
              "Work-order recovery deferred: {0}",
              unavailable.getClass().getSimpleName());
    }
  }
}
