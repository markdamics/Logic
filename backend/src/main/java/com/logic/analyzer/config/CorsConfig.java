package com.logic.analyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Defensive CORS config. During dev, the Vite dev server proxies /api to this
 * backend so requests are same-origin and this never kicks in; it becomes
 * load-bearing once frontend and backend are deployed to different origins -
 * set CORS_ALLOWED_ORIGINS to the frontend's real origin(s) in that case.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split(",");
        for (int i = 0; i < this.allowedOrigins.length; i++) {
            this.allowedOrigins[i] = this.allowedOrigins[i].trim();
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "DELETE", "PUT")
                .allowedHeaders("*");
    }
}
