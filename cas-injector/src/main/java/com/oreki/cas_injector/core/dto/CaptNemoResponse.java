package com.oreki.cas_injector.core.dto;

import java.util.Map;

import lombok.Data;

@Data
public class CaptNemoResponse {
    private String isin;
    private String name;
    private Map<String, Object> navs; // Key: "2026-03-20", Value: 125.45
}