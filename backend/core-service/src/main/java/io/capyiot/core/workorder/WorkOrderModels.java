package io.capyiot.core.workorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class WorkOrderModels {
    private WorkOrderModels(){}
    public enum Source{INCIDENT,MANUAL}
    public enum DeviceRole{MAIN,RELATED}
    public enum Priority{P1,P2,P3,P4}
    public enum AcceptanceResult{PASS,RETURN}
    public enum Status{OPEN,ASSIGNED,ACCEPTED,IN_PROGRESS,VERIFYING,RETURNED,CLOSED,CANCELLED}
    public record DeviceItem(@NotBlank String deviceId,@NotNull DeviceRole deviceRole,@NotBlank @Size(max=2000) String issueDescription){}
    public record Create(@NotNull Source sourceType,String incidentId,@NotEmpty @Size(max=100) List<@Valid DeviceItem> deviceItems,
                         @NotBlank @Size(max=160) String title,@NotBlank @Size(max=4000) String description,
                         @NotBlank @Size(max=64) String workCategory,Priority priority,@Size(max=1000) String priorityReason,
                         @NotBlank @Size(max=2000) String expectedResult,Instant dueAt){}
    public record Assign(@NotBlank String assignedRepairerId,@NotBlank String designatedAcceptorId,@NotNull @Min(0) Long expectedVersion,@Size(max=1000) String reason){}
    public record Version(@NotNull @Min(0) Long expectedVersion){}
    public record Reason(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=1000) String reason){}
    public record Repair(@NotBlank @Size(max=32) String recordType,@NotBlank @Size(max=4000) String content,
                         @Size(max=100) List<@NotBlank @Size(max=128) String> evidenceReferenceIds,@NotNull @Min(0) Long expectedVersion){}
    public record DeviceResult(@NotBlank String deviceId,@NotBlank @Size(max=2000) String result){}
    public record Completion(@NotBlank @Size(max=4000) String completionSummary,@NotEmpty @Size(max=100) List<@Valid DeviceResult> deviceResults,
                             @Size(max=100) List<@NotBlank @Size(max=128) String> evidenceReferenceIds,@NotNull @Min(0) Long expectedVersion){}
    public record Check(@NotBlank @Size(max=200) String name,boolean passed){}
    public record Acceptance(@NotNull AcceptanceResult result,@NotBlank @Size(max=1000) String comment,@NotEmpty @Size(max=100) List<@Valid Check> checkItems,
                             @Size(max=100) List<@NotBlank @Size(max=128) String> evidenceReferenceIds,@NotNull @Min(0) Long expectedVersion){}
}
