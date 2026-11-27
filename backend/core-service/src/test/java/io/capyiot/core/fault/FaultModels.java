package io.capyiot.core.fault;

import jakarta.validation.constraints.*;
import java.util.List;

public final class FaultModels {
    private FaultModels(){}
    public enum Priority{P1,P2,P3,P4}
    public enum Decision{CONFIRM,REJECT}
    public enum RootCause{UNKNOWN,UNCONFIRMED,CONFIRMED}
    public record Reason(@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion){}
    public record Review(@NotNull Decision decision,@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion){}
    public record ChangePriority(@NotNull Priority priority,@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion){}
    public record Cause(@NotNull RootCause rootCauseStatus,@Size(max=2000) String confirmedRootCauseText,
                        @Size(max=100) List<@NotBlank String> evidenceReferenceIds,@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion){}
    public record Close(@NotBlank @Size(max=64) String resolvedDisposition,@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion){}
    public record Create(@NotBlank @Size(max=160) String title,@NotEmpty @Size(max=100) List<@NotBlank String> anomalyIds,
                         @NotNull Priority priority,@NotBlank @Size(max=1000) String reason){}
}
