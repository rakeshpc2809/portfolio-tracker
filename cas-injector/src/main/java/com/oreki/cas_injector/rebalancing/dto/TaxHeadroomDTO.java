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
public class TaxHeadroomDTO {
    private BigDecimal ltcgRealizedThisFy;
    private BigDecimal ltcgHeadroomRemaining;
    private BigDecimal totalUnrealizedLtcg;
    private BigDecimal harvestableLtcg;
}
