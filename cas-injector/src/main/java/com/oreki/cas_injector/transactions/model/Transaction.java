package com.oreki.cas_injector.transactions.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oreki.cas_injector.core.model.Scheme;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "transaction")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_hash", unique = true, nullable = false)
    private String txnHash;

    @Column(name = "transaction_date")
    private LocalDate date;

    @Column(length = 500)
    private String description;

    @Column(precision = 18, scale = 4)
    private BigDecimal units;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "transaction_type")
    private String transactionType; // BUY, SELL, STAMP_DUTY

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheme_id")
    @JsonIgnoreProperties({"transactions", "folio"}) // Stop the loop here
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Scheme scheme;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Transaction parent; // Link for STAMP_DUTY -> BUY

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @ToString.Exclude // Prevent circular reference in Lombok toString
    @EqualsAndHashCode.Exclude
    private List<Transaction> children;
}