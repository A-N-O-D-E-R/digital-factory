package com.anode.factory.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * The built frontend, from spring.web.resources.static-locations (classpath:/static/ by default; a
 * local run points at factory-frontend/dist). Its routes (/work-orders/…) are the app's: they get
 * index.html, the app routes in the browser.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
class FrontendPages implements WebMvcConfigurer {

    /** The backend's own paths: never a page, so their 404s and 401s stay theirs. */
    private static final List<String> BACKEND = List.of("/api/", "/internal/", "/webhooks/", "/oauth2/", "/login/oauth2/",
            "/logout", "/actuator", "/v3/", "/error");

    static boolean isPage(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod()) && BACKEND.stream().noneMatch(path::startsWith);
    }

    private final WebProperties web;

    FrontendPages(WebProperties web) {
        this.web = web;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(web.getResources().getStaticLocations())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource file = location.createRelative(path);
                        if (file.isReadable()) {
                            return file;
                        }
                        // an app route, not a missing file (those have an extension): the location's page, if it has one
                        if (path.contains(".") || BACKEND.stream().anyMatch(b -> ("/" + path).startsWith(b))) {
                            return null;
                        }
                        Resource page = location.createRelative("index.html");
                        return page.isReadable() ? page : null;
                    }
                });
    }
}
