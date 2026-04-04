package com.oreki.cas_injector.transactions.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "capital_gain_audit")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapitalGainAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sell_transaction_id")
    private Transaction sellTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tax_lot_id")
    private TaxLot taxLot;

    @Column(precision = 18, scale = 4)
    private BigDecimal unitsMatched;

    @Column(precision = 18, scale = 2)
    private BigDecimal realizedGain;

    private String taxCategory; // LTCG, STCG
    private Long holdingPeriodDays;
}