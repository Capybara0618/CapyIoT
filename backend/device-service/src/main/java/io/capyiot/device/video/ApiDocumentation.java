package io.capyiot.device.video;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.*;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info=@Info(title="CapyIoT Device API",version="1.0",description="设备资产、拓扑、会话、事件和运行状态；派生健康有独立可用性，不替代设备事实。"))
@SecurityScheme(name="bearerAuth",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityScheme(name="serviceAuth",type=SecuritySchemeType.APIKEY,in=SecuritySchemeIn.HEADER,paramName="X-Service-Authorization")
class ApiDocumentation {
    @org.springframework.context.annotation.Bean org.springdoc.core.customizers.OpenApiCustomizer securityContract(){return api->{
        if(api.getPaths()==null)return;api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
            if(path.equals("/api/v1/device-sessions")||path.startsWith("/.well-known/")||path.startsWith("/actuator/")){operation.setSecurity(java.util.List.of());return;}
            var required=new io.swagger.v3.oas.models.security.SecurityRequirement();
            if(path.startsWith("/internal/v1/")){var endpoint=java.util.Arrays.stream(io.capyiot.contract.internal.InternalEndpoint.values()).filter(e->e.target()==io.capyiot.contract.internal.ServiceId.DEVICE&&e.template().equals(path)&&e.method().equals(method.name())).findFirst().orElseThrow();required.addList("serviceAuth");if(endpoint.actorRequired())required.addList("bearerAuth");}
            else required.addList("bearerAuth");operation.setSecurity(java.util.List.of(required));
        }));
    };}
}
