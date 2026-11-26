package io.capyiot.contract.internal;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InternalContractsTest {
    private final Instant now=Instant.parse("2026-11-26T06:00:00Z");
    private WorkOrderApi.CommandBinding binding() {
        return new WorkOrderApi.CommandBinding("operation-1","incident-1",WorkOrderApi.Operation.CREATE,
                "USER-SUPERVISOR","existing:key/with-punctuation","a".repeat(64));
    }

    @Test void unknownAndAbsentOperationsNeverProveAbort() {
        var unknown=new WorkOrderApi.OperationResult("operation-1",WorkOrderApi.OperationState.UNKNOWN,
                null,null,null,null,now);
        assertThat(unknown.provesAbort(binding())).isFalse();
        var aborted=new WorkOrderApi.OperationResult("operation-1",WorkOrderApi.OperationState.ABORTED,
                binding(),"reservation-1",null,null,now);
        assertThat(aborted.provesAbort(binding())).isTrue();
        var different=new WorkOrderApi.CommandBinding("operation-1","incident-1",WorkOrderApi.Operation.CREATE,
                "USER-OPERATOR","another-key","b".repeat(64));
        assertThat(aborted.provesAbort(different)).isFalse();
        assertThatThrownBy(()->new WorkOrderApi.OperationResult("operation-1",WorkOrderApi.OperationState.COMMITTED,
                binding(),null,null,null,now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void partialOrPendingAuthoritySnapshotCannotAuthorizeClosure() {
        var closed=new WorkOrderApi.OrderRef("1","WO-000001",WorkOrderApi.OrderStatus.CLOSED,7);
        assertThat(new WorkOrderApi.IncidentOrderState("incident-1",List.of(closed),List.of(),true,now,null).allTerminal()).isTrue();
        assertThat(new WorkOrderApi.IncidentOrderState("incident-1",List.of(closed),List.of(),false,now,null).allTerminal()).isFalse();
        assertThat(new WorkOrderApi.IncidentOrderState("incident-1",List.of(closed),List.of("operation-1"),true,now,null).allTerminal()).isFalse();
        assertThat(new WorkOrderApi.IncidentOrderState("incident-1",List.of(closed),List.of(),true,now,"page-2").allTerminal()).isFalse();
        var active=new WorkOrderApi.OrderRef("2","WO-000002",WorkOrderApi.OrderStatus.VERIFYING,2);
        assertThat(new WorkOrderApi.IncidentOrderState("incident-1",List.of(active),List.of(),true,now,null).allTerminal()).isFalse();
    }

    @Test void batchesAreBoundedDistinctAndCannotBeMutatedAfterValidation() {
        var ids=new ArrayList<>(List.of("IPC-1"));
        var batch=new DeviceApi.Batch(ids); ids.add("IPC-2");
        assertThat(batch.deviceIds()).containsExactly("IPC-1");
        assertThatThrownBy(()->batch.deviceIds().add("IPC-3")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new DeviceApi.Batch(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DeviceApi.Batch(List.of("IPC-1","IPC-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DeviceApi.Batch(IntStream.range(0,101).mapToObj(n->"IPC-"+n).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fixedEndpointPolicyRejectsPathInjectionAndUnrelatedCallers() {
        assertThat(InternalEndpoint.RESERVATION_CREATE.path("incident-1"))
                .isEqualTo("/internal/v1/incidents/incident-1/reservations");
        for(String id:List.of("../users","https://example.org","IPC%2f1","IPC?x=1"))
            assertThatThrownBy(()->InternalEndpoint.INCIDENT_REFERENCE.path(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(InternalEndpoint.RESERVATION_CREATE.allows(ServiceId.AGENT)).isFalse();
        assertThat(InternalEndpoint.RESERVATION_CREATE.allows(ServiceId.WORKFLOW)).isTrue();
        assertThat(InternalEndpoint.RESERVATION_COMMIT.query()).isFalse();
        assertThat(InternalEndpoint.HEALTH_BATCH.actorRequired()).isFalse();
        assertThat(InternalEndpoint.DEVICE_SCOPE.actorRequired()).isTrue();
        assertThat(InternalEndpoint.RESERVATION_CREATE.matches("GET",InternalEndpoint.RESERVATION_CREATE.path("incident-1"))).isFalse();
    }

    @Test void malformedJsonCannotBypassConstructorGuards() throws Exception {
        var mapper=JsonMapper.builder().addModule(new JavaTimeModule()).build();
        assertThatThrownBy(()->mapper.readValue("{\"deviceIds\":[\"../admin\"]}",DeviceApi.Batch.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        var value=new WorkOrderApi.OperationResult("operation-1",WorkOrderApi.OperationState.RECONCILIATION_REQUIRED,
                binding(),"reservation-1",null,null,now);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(value),WorkOrderApi.OperationResult.class)).isEqualTo(value);
    }

    @Test void platformGrantCannotAccidentallyAcquireBusinessScope() {
        assertThatThrownBy(()->new PlatformApi.Grant("g-1","SYS_ADMIN",PlatformApi.ScopeType.PARK,
                "CAMPUS-A",List.of(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PlatformApi.Grant("g-1","OPS_SUPERVISOR",PlatformApi.ScopeType.NONE,
                null,List.of(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PlatformApi.Grant("g-1","OPS_SUPERVISOR",PlatformApi.ScopeType.AREA_SUBTREE,
                "CAMPUS-A",List.of(),null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void producerMigrationOnlyAcceptsTheOwnerOrExplicitHistoricalCore() {
        assertThat(DomainProducer.DEVICE.accepts("capyiot.domain.device.v1","device","device-service",false)).isTrue();
        assertThat(DomainProducer.DEVICE.accepts("capyiot.domain.device.v1","device","core-service",true)).isTrue();
        assertThat(DomainProducer.DEVICE.accepts("capyiot.domain.device.v1","device","core-service",false)).isFalse();
        assertThat(DomainProducer.DEVICE.accepts("capyiot.domain.device.v1","device","workflow-service",true)).isFalse();
        assertThat(DomainProducer.DEVICE.accepts("capyiot.domain.fault.v1","fault","device-service",false)).isFalse();
    }

    @Test void publishedOpenApiAgreesWithExecutableEndpointPolicy() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var input=getClass().getResourceAsStream("/contracts/internal-api.openapi.json")) {
            assertThat(input).isNotNull();var spec=mapper.readTree(input);
            for(var endpoint:InternalEndpoint.values()) {
                var operation=spec.path("paths").path(endpoint.template()).path(endpoint.method().toLowerCase(java.util.Locale.ROOT));
                assertThat(operation.path("operationId").asText()).isEqualTo(endpoint.name());
                assertThat(operation.path("x-capyiot-query").asBoolean()).isEqualTo(endpoint.query());
                assertThat(operation.path("x-capyiot-scope").asText()).isEqualTo(endpoint.scope());
                assertThat(operation.path("security").get(0).has("ActorToken")).isEqualTo(endpoint.actorRequired());
                var callers=new java.util.HashSet<String>();operation.path("x-capyiot-callers").forEach(c->callers.add(c.asText()));
                for(var service:ServiceId.values())assertThat(callers.contains(service.value())).isEqualTo(endpoint.allows(service));
            }
        }
    }
}
