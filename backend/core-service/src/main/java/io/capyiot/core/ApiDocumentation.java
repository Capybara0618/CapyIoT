package io.capyiot.core;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info=@Info(title="CapyIoT Core API",version="1.0",description="设备资产、异常故障、维修验收与平台身份；每次请求重新校验当前用户和数据范围。写入要求幂等键及请求追踪标识。"),security=@SecurityRequirement(name="bearerAuth"))
@SecurityScheme(name="bearerAuth",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
class ApiDocumentation {}
