package com.oreki.cas_injector.stocks.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedTransactionDTO {
    private LocalDate transactionDate;
    private String ticker;
    private String isin;
    private String exchange;
    private String transactionType;
    private BigDecimal quantity;
    private BigDecimal pricePerShare;
    private BigDecimal brokerage;
    private BigDecimal stt;
    private BigDecimal totalAmount;
    private String source;
}
