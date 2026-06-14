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
public class PortfolioStateItemDTO {
    private String schemeName;
    private String amfiCode;
    private String isin;
    private BigDecimal totalUnits;
    private BigDecimal lockedStcgUnits;
    private BigDecimal harvestableLtcgUnits;
    private BigDecimal totalLtcgUnits;
}
