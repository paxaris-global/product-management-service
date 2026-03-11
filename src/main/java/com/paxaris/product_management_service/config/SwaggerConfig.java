    package com.paxaris.product_management_service.config;

    import io.swagger.v3.oas.models.OpenAPI;
    import io.swagger.v3.oas.models.info.Info;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.web.client.RestTemplate;

    @Configuration
    public class SwaggerConfig {

        @Value("${springdoc.info.title}")
        private String apiTitle;

        @Value("${springdoc.info.version}")
        private String apiVersion;

        @Value("${springdoc.info.description}")
        private String apiDescription;

        @Bean
        public OpenAPI customOpenAPI() {
            return new OpenAPI()
                    .info(new Info()
                    .title(apiTitle)
                    .version(apiVersion)
                    .description(apiDescription));
        }
        @Bean
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }
