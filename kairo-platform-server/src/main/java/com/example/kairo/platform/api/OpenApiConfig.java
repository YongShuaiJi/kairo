package com.example.kairo.platform.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * OpenAPI contract configuration (V1.6 &sect;5.1 "OpenAPI 由代码/契约统一生成或校验").
 * The contract is generated from the controllers + DTOs at {@code /v3/api-docs} so
 * the Web BFF, CLI, SDK and MCP clients share one machine-readable source of truth.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI kairoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kairo Platform API")
                        .version("1.6")
                        .description("V1.6 API-First / AI-First public API. External clients (Web, CLI, SDK, MCP) "
                                + "are all clients of this API and share RBAC, audit, idempotency and risk control."))
                .servers(java.util.List.of(new Server().url("/").description("Platform API root")))
                .schemaRequirement(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("opaque")
                        .description("Platform access token (least-privilege); scoped tokens narrow capabilities."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** Publish the actual V1 RBAC expression on every operation for compatibility diffing. */
    @Bean
    OpenApiCustomizer kairoAuthorizationContract() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                    .forEach((method, operation) -> operation.addExtension(
                            KairoApiAuthorizationCatalog.EXTENSION,
                            KairoApiAuthorizationCatalog.requirement(
                                    method.name().toLowerCase(java.util.Locale.ROOT), path))));
        };
    }
}
