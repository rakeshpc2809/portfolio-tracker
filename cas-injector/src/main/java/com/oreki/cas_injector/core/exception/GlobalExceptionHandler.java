package com.oreki.cas_injector.core.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Catches issues with external APIs (like mfapi.in timing out or throwing 502s)
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiError> handleRestClientException(RestClientException ex, HttpServletRequest request) {
        log.error("External API Error: {}", ex.getMessage());
        
        ApiError apiError = new ApiError(
            LocalDateTime.now(),
            HttpStatus.BAD_GATEWAY.value(),
            "Bad Gateway - External Service Failure",
            "Failed to communicate with an external API (e.g., mfapi.in). Please try again later.",
            request.getRequestURI()
        );
        
        return new ResponseEntity<>(apiError, HttpStatus.BAD_GATEWAY);
    }

    /**
     * Catches bad inputs, like uploading an empty PDF or invalid JSON
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Bad Request: {}", ex.getMessage());
        
        ApiError apiError = new ApiError(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            ex.getMessage(),
            request.getRequestURI()
        );
        
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    /**
     * The Ultimate Fallback: Catches absolutely everything else (NullPointers, SQL errors, etc.)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGlobalException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled Exception caught!", ex);
        
        ApiError apiError = new ApiError(
            LocalDateTime.now(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An unexpected error occurred: " + ex.getMessage(),
            request.getRequestURI()
        );
        
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}