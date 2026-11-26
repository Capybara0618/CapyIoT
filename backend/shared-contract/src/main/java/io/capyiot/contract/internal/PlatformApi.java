package io.capyiot.contract.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class PlatformApi {
    private PlatformApi() {}
    public enum ScopeType { NONE, PARK, AREA_SUBTREE, ASSIGNED_TO_ME }
    public enum AssignmentRelation { REPAIRER, ACCEPTOR }
    public enum PersonnelRole { REPAIR_TECH, ACCEPTANCE_STAFF }

    /** The actor credential is in Authorization, never in this payload or response. */
    public record IdentityRequest() {}

    public record Grant(String grantId, String roleCode, ScopeType scopeType, String parkId,
                        List<String> rootAreaIds, AssignmentRelation assignmentRelation) {
        public Grant {
            ContractChecks.id(grantId); ContractChecks.id(roleCode); ContractChecks.required(scopeType);
            rootAreaIds = ContractChecks.ids(rootAreaIds, true);
            if (scopeType == ScopeType.NONE) {
                if (!roleCode.equals("SYS_ADMIN") || parkId != null || !rootAreaIds.isEmpty() || assignmentRelation != null)
                    throw new IllegalArgumentException("Invalid platform grant");
            } else {
                ContractChecks.id(parkId);
                if (roleCode.equals("SYS_ADMIN") || (scopeType == ScopeType.AREA_SUBTREE) != !rootAreaIds.isEmpty()
                        || (scopeType == ScopeType.ASSIGNED_TO_ME) != (assignmentRelation != null))
                    throw new IllegalArgumentException("Invalid resource grant");
            }
        }
    }

    public record Identity(String userId, String loginName, String displayName, long authorizationVersion,
                           String sessionId, List<Grant> grants, Set<String> permissionCodes, Instant checkedAt) {
        public Identity {
            ContractChecks.id(userId); ContractChecks.text(loginName,64); ContractChecks.text(displayName,100);
            if (authorizationVersion < 1) throw new IllegalArgumentException("Invalid authorization version");
            ContractChecks.text(sessionId,128); grants = List.copyOf(grants);
            if (grants.size() > 1000) throw new IllegalArgumentException("Too many grants");
            permissionCodes = Set.copyOf(permissionCodes); permissionCodes.forEach(ContractChecks::permission);
            ContractChecks.required(checkedAt);
        }
    }

    public record EligibilityRequest(List<String> candidateUserIds, PersonnelRole role,
                                     List<DeviceApi.Resource> devices) {
        public EligibilityRequest {
            candidateUserIds = ContractChecks.ids(candidateUserIds,true);
            ContractChecks.required(role); devices = ContractChecks.batch(devices,false);
        }
    }

    public record Eligibility(String userId, String displayName, PersonnelRole role, boolean eligible) {
        public Eligibility { ContractChecks.id(userId); ContractChecks.text(displayName,100); ContractChecks.required(role); }
    }
    public record EligibilityResult(List<Eligibility> candidates, Instant checkedAt) {
        public EligibilityResult { candidates=ContractChecks.batch(candidates,true); ContractChecks.required(checkedAt); }
    }
}
