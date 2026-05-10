package com.oreki.cas_injector.stocks.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "stock_price_eod")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(StockPriceEod.StockPriceId.class)
public class StockPriceEod {
    @Id
    private String ticker;

    @Id
    @Column(name = "price_date")
    private LocalDate priceDate;

    @Column(name = "open_price")
    private BigDecimal openPrice;

    @Column(name = "high_price")
    private BigDecimal highPrice;

    @Column(name = "low_price")
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false)
    private BigDecimal closePrice;

    private Long volume;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockPriceId implements Serializable {
        private String ticker;
        private LocalDate priceDate;
    }
}
