package com.midhudsonfiber.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The React build is bundled into this jar's static resources, so client-side
 * routes have to fall through to index.html rather than 404. API paths are left
 * alone — a wrong URL under /api should still be an honest 404.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:^(?!api|actuator|app-assets)[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|actuator|app-assets)[^\\.]*}/**").setViewName("forward:/index.html");
    }
}
