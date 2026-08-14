package com.dcbate.tradingplatform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Powers the Swagger UI at {@code /v1/swagger-ui.html} — title/description plus the bearer-JWT auth scheme every endpoint expects. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradingPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trading Platform API")
                        .description("Real-time order submission, matching, and trade execution")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
