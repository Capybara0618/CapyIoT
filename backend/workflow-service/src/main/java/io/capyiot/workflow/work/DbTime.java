package io.capyiot.workflow.work;

import java.sql.Timestamp;
import java.time.*;

public final class DbTime {
  private DbTime() {}

  public static LocalDateTime sql(Instant value) {
    return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  public static Instant instant(Object value) {
    return switch (value) {
      case null -> null;
      case Timestamp time -> time.toInstant();
      case LocalDateTime time -> time.toInstant(ZoneOffset.UTC);
      case Instant time -> time;
      default -> throw new IllegalArgumentException("Unsupported database time");
    };
  }
}
