package io.capyiot.legacyworkflow;

import io.capyiot.contract.AlarmEvent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class WorkOrderModels {
    private WorkOrderModels() {}

    public enum Status { OPEN, ASSIGNED, IN_PROGRESS, VERIFYING, CLOSED }
    public enum Priority { HIGH, MEDIUM, LOW }
    public enum Action {
        ASSIGN, REASSIGN, START_PROCESSING, ADD_NOTE, COMPLETE_PROCESSING,
        VERIFY_RECOVERY, RETURN_TO_PROCESSING, CLOSE, REOPEN
    }

    public record Create(@NotNull @Positive Long sourceAlarmId, @Size(max = 160) String title,
            Priority priority, @Size(max = 100) String assignee) {}
    public record Transition(@NotNull @Min(0) Long expectedVersion, @NotNull Action action,
            @Size(max = 100) String assignee, @Size(max = 1000) String remark) {}
    public record Created(Order order, boolean created) {}
    public record Actor(String campus, String username, boolean admin) {}

    public record Order(long id, String orderNo, long sourceAlarmId, String sourceAlarmNo, String title,
            Priority priority, Status status, String assignee, String createdBy, long version,
            Long verifiedAlarmVersion, Instant verifiedAt, Instant closedAt,
            Instant createdAt, Instant updatedAt, AlarmEvent.Source source) {
        public boolean recoveryVerified() {
            return source != null && source.recovered() && verifiedAlarmVersion != null
                    && verifiedAlarmVersion == source.version();
        }
    }

    public record Summary(long total, long pending, long processing, long verifying, long closed) {}
    public record Entry(long version, String action, Status fromStatus, Status toStatus, String actor,
            String remark, Instant createdAt, String assignee) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Detail(Order order, Page<Entry> timeline, List<Action> actions, boolean recoveryVerified) {}
}
