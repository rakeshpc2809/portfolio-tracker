package com.oreki.cas_injector.rebalancing.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RebalanceActionDTO {
    private String schemeName;
    private String amfiCode;
    private String isin;
    private String signal;
    private BigDecimal unitsToTransact;
    private BigDecimal amount;
    private String justification;
    private BigDecimal zScore;
    private BigDecimal hurstExponent;
}
