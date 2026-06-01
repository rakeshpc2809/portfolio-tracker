package com.oreki.cas_injector.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class JwtService {

    @Value("${portfolio.jwt.secret:default-secret-key-32-chars-long-or-more-portfolio-os}")
    private String jwtSecret;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateToken(String pan) {
        try {
            Map<String, Object> header = new HashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", pan);
            payload.put("pan", pan);
            payload.put("iat", System.currentTimeMillis() / 1000);
            payload.put("exp", (System.currentTimeMillis() / 1000) + 86400); // 24 hours

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsString(header).getBytes(StandardCharsets.UTF_8)
            );
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)
            );

            String signature = hmacSha256(encodedHeader + "." + encodedPayload, jwtSecret);

            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            log.error("Failed to generate JWT token", e);
            throw new RuntimeException("JWT generation failed", e);
        }
    }

    public String extractPan(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] decodedPayload = Base64.getUrlDecoder().decode(parts[1]);
            Map<?, ?> payload = objectMapper.readValue(decodedPayload, Map.class);
            return (String) payload.get("pan");
        } catch (Exception e) {
            log.warn("Failed to extract PAN from JWT", e);
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            String encodedHeader = parts[0];
            String encodedPayload = parts[1];
            String signature = parts[2];

            // Re-calculate signature
            String expectedSignature = hmacSha256(encodedHeader + "." + encodedPayload, jwtSecret);
            if (!expectedSignature.equals(signature)) {
                return false;
            }

            // Check expiration
            byte[] decodedPayload = Base64.getUrlDecoder().decode(encodedPayload);
            Map<?, ?> payload = objectMapper.readValue(decodedPayload, Map.class);
            Number exp = (Number) payload.get("exp");
            if (exp != null && exp.longValue() < (System.currentTimeMillis() / 1000)) {
                return false; // Expired
            }

            return true;
        } catch (Exception e) {
            log.warn("Token validation failed", e);
            return false;
        }
    }

    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
