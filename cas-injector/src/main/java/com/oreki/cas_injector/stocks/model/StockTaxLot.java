package com.oreki.cas_injector.stocks.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_tax_lot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTaxLot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buy_transaction_id")
    private StockTransaction buyTransaction;

    @Column(name = "buy_date", nullable = false)
    private LocalDate buyDate;

    @Column(name = "original_qty", nullable = false)
    private BigDecimal originalQty;

    @Column(name = "remaining_qty", nullable = false)
    private BigDecimal remainingQty;

    @Column(name = "cost_basis_per_share", nullable = false)
    private BigDecimal costBasisPerShare;

    @Column(name = "adjusted_cost_basis")
    private BigDecimal adjustedCostBasis;

    @Builder.Default
    private String status = "OPEN"; // OPEN, PARTIAL, CLOSED

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
