package com.oreki.cas_injector.stocks.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_capital_gain")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockCapitalGain {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buy_lot_id")
    private StockTaxLot buyLot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sell_transaction_id")
    private StockTransaction sellTransaction;

    @Column(name = "buy_date", nullable = false)
    private LocalDate buyDate;

    @Column(name = "sell_date", nullable = false)
    private LocalDate sellDate;

    @Column(name = "quantity_sold", nullable = false)
    private BigDecimal quantitySold;

    @Column(name = "buy_price", nullable = false)
    private BigDecimal buyPrice;

    @Column(name = "sell_price", nullable = false)
    private BigDecimal sellPrice;

    @Column(name = "realized_gain", nullable = false)
    private BigDecimal realizedGain;

    @Column(name = "gain_type")
    private String gainType; // LTCG, STCG

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(name = "tax_estimate")
    private BigDecimal taxEstimate;

    private String fy;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
