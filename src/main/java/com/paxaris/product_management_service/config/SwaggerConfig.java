    package com.paxaris.product_management_service.config;

    import io.swagger.v3.oas.models.OpenAPI;
    import io.swagger.v3.oas.models.info.Info;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.web.client.RestTemplate;

    @Configuration
    public class SwaggerConfig {

        @Bean
        public OpenAPI customOpenAPI() {
            return new OpenAPI()
                    .info(new Info()
                            .title("Your API Title")
                            .version("1.0")
                            .description("A detailed description of your API."));
        }
        @Bean
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }
