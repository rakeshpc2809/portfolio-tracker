package com.oreki.cas_injector.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    @Value("${portfolio.api.key:dev-secret-key}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Skip API key check for OPTIONS requests (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Skip API key check for error path and health checks
        String path = request.getRequestURI();
        if (path.endsWith("/error") || path.contains("/actuator") || path.contains("/health") || path.contains("/status") || path.contains("/ws")) {
            return true;
        }

        String requestApiKey = request.getHeader("X-API-KEY");
        if (apiKey.equals(requestApiKey)) {
            return true;
        }

        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized: Invalid or missing API Key");
        return false;
    }
}
