package io.capyiot.platform;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info=@Info(title="CapyIoT Platform API",version="1.0",description="用户、权限、会话和审计；资源事实通过设备服务核查。内部接口需要独立服务凭证。"))
@SecurityScheme(name="bearerAuth",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityScheme(name="serviceAuth",type=SecuritySchemeType.APIKEY,in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Service-Authorization")
class ApiDocumentation {
    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OpenApiCustomizer platformSecurityDocumentation(){return api->{
        if(api.getPaths()==null)return;
        api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
            if(path.startsWith("/.well-known/")||path.startsWith("/actuator/")){operation.setSecurity(java.util.List.of());return;}
            var required=new io.swagger.v3.oas.models.security.SecurityRequirement();
            if(path.startsWith("/internal/v1/")){
                var endpoint=java.util.Arrays.stream(io.capyiot.contract.internal.InternalEndpoint.values())
                        .filter(e->e.target()==io.capyiot.contract.internal.ServiceId.PLATFORM&&e.template().equals(path)&&e.method().equals(method.name())).findFirst().orElseThrow();
                required.addList("serviceAuth");if(endpoint.actorRequired())required.addList("bearerAuth");
            }else required.addList("bearerAuth");
            operation.setSecurity(java.util.List.of(required));
        }));
    };}
}
