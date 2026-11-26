package io.capyiot.core;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info=@Info(title="CapyIoT Core API",version="1.0",description="迁移中的异常故障与维修验收接口；平台与设备已拆出，不代表各服务的聚合文档。写入要求幂等键及请求追踪标识。"),security=@SecurityRequirement(name="bearerAuth"))
@SecurityScheme(name="bearerAuth",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
class ApiDocumentation {}
