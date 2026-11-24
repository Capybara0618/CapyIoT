package io.capyiot.core.platform;

import io.capyiot.core.ApiProblem;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record AuthorizationContext(String userId,String loginName,String displayName,long authorizationVersion,
                                   String sessionId,List<Grant> grants) {
    public record Grant(String grantId,String roleCode,String scopeType,String parkId,List<String> rootAreaIds,String assignmentRelation) {
        public boolean permits(String permission) { return Roles.PERMISSIONS.getOrDefault(roleCode,Set.of()).contains(permission); }
    }
    public Set<String> roles() { return grants.stream().map(Grant::roleCode).collect(Collectors.toUnmodifiableSet()); }
    public Set<String> permissions() { return grants.stream().flatMap(g->Roles.PERMISSIONS.getOrDefault(g.roleCode(),Set.of()).stream()).collect(Collectors.toUnmodifiableSet()); }
    public List<Grant> require(String permission) {
        var allowed=grants.stream().filter(g->g.permits(permission)).toList();
        if(allowed.isEmpty()) throw ApiProblem.forbidden();
        return allowed;
    }
    public void requirePlatform(String permission) {
        if(require(permission).stream().noneMatch(g->g.roleCode().equals("SYS_ADMIN")&&g.scopeType().equals("NONE"))) throw ApiProblem.forbidden();
    }
}
