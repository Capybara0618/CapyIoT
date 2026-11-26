package io.capyiot.contract.internal;

import java.util.Set;
import java.util.regex.Pattern;

/** Fixed internal operations, not a caller-controlled URL or a substitute for business authorization. */
public enum InternalEndpoint {
    IDENTITY_CONTEXT(ServiceId.PLATFORM, "POST", "/identity/context", true, true, "identity.read",
            ServiceId.GATEWAY, ServiceId.DEVICE, ServiceId.FAULT, ServiceId.WORKFLOW, ServiceId.AGENT),
    USER_ELIGIBILITY(ServiceId.PLATFORM, "POST", "/users/eligibility", true, true, "users.eligibility",
            ServiceId.WORKFLOW),
    RESOURCE_VALIDATION(ServiceId.DEVICE, "POST", "/resources/validate", true, false, "resources.validate",
            ServiceId.PLATFORM),
    DEVICE_SCOPE(ServiceId.DEVICE, "POST", "/devices/scope-check", true, true, "devices.scope",
            ServiceId.FAULT, ServiceId.WORKFLOW, ServiceId.PLATFORM),
    TOPOLOGY_SNAPSHOT(ServiceId.DEVICE, "POST", "/topology/snapshot", true, false, "topology.snapshot",
            ServiceId.FAULT),
    RUNTIME_SNAPSHOT(ServiceId.DEVICE, "POST", "/devices/runtime-snapshot", true, false, "runtime.snapshot",
            ServiceId.FAULT),
    HEALTH_BATCH(ServiceId.FAULT, "POST", "/health/batch", true, false, "health.read",
            ServiceId.DEVICE),
    EVIDENCE_RESOLVE(ServiceId.FAULT, "POST", "/evidence/resolve", true, true, "evidence.resolve",
            ServiceId.WORKFLOW),
    INCIDENT_REFERENCE(ServiceId.FAULT, "GET", "/incidents/{id}", true, true, "incidents.read",
            ServiceId.WORKFLOW),
    RESERVATION_CREATE(ServiceId.FAULT, "POST", "/incidents/{id}/reservations", false, true, "reservations.create",
            ServiceId.WORKFLOW),
    RESERVATION_QUERY(ServiceId.FAULT, "GET", "/operations/{id}/reservation", true, false, "reservations.read",
            ServiceId.WORKFLOW),
    RESERVATION_COMMIT(ServiceId.FAULT, "POST", "/reservations/{id}/commit", false, false, "reservations.commit",
            ServiceId.WORKFLOW),
    RESERVATION_ABORT(ServiceId.FAULT, "POST", "/reservations/{id}/abort-ack", false, false, "reservations.abort",
            ServiceId.WORKFLOW),
    ASSIGNMENT_FACTS(ServiceId.WORKFLOW, "POST", "/assignments/scope-facts", true, false, "assignments.read",
            ServiceId.DEVICE),
    INCIDENT_ORDER_STATE(ServiceId.WORKFLOW, "GET", "/incidents/{id}/order-state", true, false, "orders.authority",
            ServiceId.FAULT),
    OPERATION_QUERY(ServiceId.WORKFLOW, "GET", "/operations/{id}", true, false, "operations.read",
            ServiceId.FAULT);

    public static final String PREFIX = "/internal/v1";
    public static final String SERVICE_AUTHORIZATION = "X-Service-Authorization";
    public static final String DEADLINE_HEADER = "X-CapyIoT-Deadline";
    private final ServiceId target;
    private final String method;
    private final String template;
    private final boolean query;
    private final boolean actorRequired;
    private final String scope;
    private final Set<ServiceId> callers;
    private final Pattern pathPattern;

    InternalEndpoint(ServiceId target, String method, String path, boolean query, boolean actorRequired,
                     String scope, ServiceId... callers) {
        this.target = target;
        this.method = method;
        this.template = "/internal/v1" + path;
        this.query = query;
        this.actorRequired = actorRequired;
        this.scope = scope;
        this.callers = Set.of(callers);
        String[] parts = template.split("\\{[^}]+\\}", -1);
        this.pathPattern = Pattern.compile(String.join("[A-Za-z0-9_-]{1,64}",
                java.util.Arrays.stream(parts).map(Pattern::quote).toList()));
    }

    public ServiceId target() { return target; }
    public String method() { return method; }
    public String template() { return template; }
    public boolean query() { return query; }
    public boolean actorRequired() { return actorRequired; }
    public String scope() { return scope; }
    public boolean allows(ServiceId caller) { return callers.contains(caller); }
    public boolean matches(String method, String path) { return this.method.equals(method) && pathPattern.matcher(path).matches(); }

    public String path(String... identifiers) {
        String path = template;
        for (String identifier : identifiers) {
            if (!path.contains("{")) throw new IllegalArgumentException("Too many path identifiers");
            path = path.replaceFirst("\\{[^}]+\\}", ContractChecks.id(identifier));
        }
        if (path.contains("{")) throw new IllegalArgumentException("Missing path identifier");
        return path;
    }
}
