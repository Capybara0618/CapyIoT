package io.capyiot.core;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.*;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Query projections have explicit public schemas, rather than an undocumented Object. */
@Configuration
class ReadModelDocumentation {
    @Bean OpenApiCustomizer readModels(){return api->{
        if(api.getComponents()==null)api.setComponents(new Components());
        api.getPaths().entrySet().removeIf(entry->!entry.getKey().startsWith("/api/v1/"));
        Map<String,Class<?>> get=Map.ofEntries(
            Map.entry("/api/v1/devices",ApiViews.DevicePage.class),Map.entry("/api/v1/devices/{id}",ApiViews.DeviceDetail.class),
            Map.entry("/api/v1/topology/context",ApiViews.Topology.class),Map.entry("/api/v1/devices/{id}/status-history",ApiViews.StateHistoryPage.class),
            Map.entry("/api/v1/events",ApiViews.EventPage.class),Map.entry("/api/v1/devices/{id}/events",ApiViews.EventPage.class),Map.entry("/api/v1/events/{eventId}",ApiViews.Event.class),
            Map.entry("/api/v1/anomalies",ApiViews.AnomalyPage.class),Map.entry("/api/v1/anomalies/{id}",ApiViews.Anomaly.class),
            Map.entry("/api/v1/incidents",ApiViews.IncidentPage.class),Map.entry("/api/v1/incidents/{id}",ApiViews.Incident.class),
            Map.entry("/api/v1/workorders",ApiViews.WorkOrderPage.class),Map.entry("/api/v1/workorders/{id}",ApiViews.WorkOrder.class),
            Map.entry("/api/v1/devices/{deviceId}/maintenance-history",ApiViews.WorkOrderPage.class),Map.entry("/api/v1/audit-records",ApiViews.AuditPage.class));
        Map<String,Class<?>> arrays=Map.of("/api/v1/areas",ApiViews.Area.class,"/api/v1/monitoring-points",ApiViews.Point.class,"/api/v1/monitoring-points/{id}/bindings",ApiViews.Binding.class);
        for(var entry:api.getPaths().entrySet())for(var operation:entry.getValue().readOperationsMap().entrySet()){
            var op=operation.getValue();String method=operation.getKey().name(),path=entry.getKey();Schema<?> data=null;
            if(method.equals("GET")&&get.containsKey(path))data=schema(api,get.get(path));
            if(method.equals("GET")&&arrays.containsKey(path))data=new ArraySchema().items(schema(api,arrays.get(path)));
            if(!method.equals("GET")&&!method.equals("DELETE")){
                if(path.equals("/api/v1/workorders"))data=schema(api,ApiViews.CreatedWorkOrder.class);
                else if(path.equals("/api/v1/devices"))data=method.equals("POST")?schema(api,ApiViews.RegisteredDevice.class):schema(api,ApiViews.DeviceSummary.class);
                else if(method.equals("PATCH")&&path.equals("/api/v1/devices/{id}"))data=schema(api,ApiViews.DeviceSummary.class);
                else if(!path.startsWith("/api/v1/auth/")&&!path.startsWith("/api/v1/users")&&!path.startsWith("/api/v1/grants/")&&!path.equals("/api/v1/device-sessions"))data=schema(api,ApiViews.CommandResult.class);
            }
            if(data!=null){
                var envelope=new ObjectSchema().addProperty("code",new StringSchema().example("OK")).addProperty("message",new StringSchema()).addProperty("traceId",new StringSchema()).addProperty("data",data);
                if(op.getResponses()==null)op.setResponses(new ApiResponses());
                var response=new ApiResponse().description("当前授权范围内的业务结果；幂等重放返回原结果，一次性密钥不重复返回").content(new Content().addMediaType("application/json",new MediaType().schema(envelope)));
                op.getResponses().addApiResponse("200",response);
                if(method.equals("POST")&&(Set.of("/api/v1/devices","/api/v1/areas","/api/v1/monitoring-points","/api/v1/network-dependencies","/api/v1/power-dependencies","/api/v1/video-channel-associations","/api/v1/incidents","/api/v1/workorders").contains(path)||path.matches("/api/v1/monitoring-points/\\{[^}]+\\}/bindings")))op.getResponses().addApiResponse("201",response);
            }
            if(path.equals("/api/v1/device-sessions"))op.setSecurity(List.of());
        }
    };}
    private static Schema<?> schema(io.swagger.v3.oas.models.OpenAPI api,Class<?> type){
        var resolved=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        resolved.referencedSchemas.forEach((name,schema)->api.getComponents().addSchemas(name,schema));return resolved.schema;
    }
}
