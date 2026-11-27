package io.capyiot.alarm.fault;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.*;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "CapyIoT Fault API",
            version = "1.0",
            description = "异常、故障研判与关闭门禁；预约结果须与工单权威操作绑定，未知状态保留待对账。"))
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
@SecurityScheme(
    name = "serviceAuth",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "X-Service-Authorization")
class ApiDocumentation {
  @org.springframework.context.annotation.Bean
  org.springdoc.core.customizers.OpenApiCustomizer securityContract() {
    return api -> {
      if (api.getPaths() == null) return;
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            if (path.startsWith("/.well-known/") || path.startsWith("/actuator/")) {
                              operation.setSecurity(java.util.List.of());
                              return;
                            }
                            var required =
                                new io.swagger.v3.oas.models.security.SecurityRequirement();
                            if (path.startsWith("/internal/v1/")) {
                              var endpoint =
                                  java.util.Arrays.stream(
                                          io.capyiot.contract.internal.InternalEndpoint.values())
                                      .filter(
                                          e ->
                                              e.target()
                                                      == io.capyiot.contract.internal.ServiceId
                                                          .FAULT
                                                  && e.template().equals(path)
                                                  && e.method().equals(method.name()))
                                      .findFirst()
                                      .orElseThrow();
                              required.addList("serviceAuth");
                              if (endpoint.actorRequired()) required.addList("bearerAuth");
                            } else required.addList("bearerAuth");
                            operation.setSecurity(java.util.List.of(required));
                          }));
    };
  }
}
