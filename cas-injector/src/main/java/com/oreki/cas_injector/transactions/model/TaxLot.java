package com.oreki.cas_injector.transactions.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.oreki.cas_injector.core.model.Scheme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tax_lot", indexes = {
    @Index(name = "idx_lot_scheme_status", columnList = "scheme_id, status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction buyTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheme_id")
    private Scheme scheme;

    private LocalDate buyDate;

    @Column(precision = 18, scale = 4)
    private BigDecimal originalUnits;

    @Column(precision = 18, scale = 4)
    private BigDecimal remainingUnits;

    @Column(precision = 18, scale = 4)
    private BigDecimal costBasisPerUnit; // (Buy Amt + Stamp Duty) / Units

    private String status; // OPEN, PARTIAL, CLOSED

}