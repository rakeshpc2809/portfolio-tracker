package com.oreki.cas_injector.stocks.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_transaction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType; // BUY, SELL, BONUS, SPLIT

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "price_per_share", nullable = false)
    private BigDecimal pricePerShare;

    private BigDecimal brokerage;
    private BigDecimal stt;
    private BigDecimal stampDuty;
    private BigDecimal otherCharges;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Builder.Default
    private String source = "INDMONEY_CSV";

    @Column(name = "external_trade_id")
    private String externalTradeId;

    private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
