package com.oreki.cas_injector.core.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SchemeDetailsDTO {
    private BigDecimal nav;
    private String category;
    private String schemeName;
}