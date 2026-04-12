package com.oreki.cas_injector.core.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.model.Transaction;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor // Required by JPA
@AllArgsConstructor // Required by Lombok @Builder
@Builder // Provides the .builder() method
public class Scheme {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true)
    private String isin;
    
    private String name;

    @Column(name = "amfi_code")
    private String amfiCode;

    @Column(name = "asset_category")
    private String assetCategory; // <--- Moved here!

    @ManyToOne 
    @JoinColumn(name = "folio_id")
    @JsonIgnoreProperties({"schemes", "investor"}) // Stop the loop here
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Folio folio;

    @OneToMany(mappedBy = "scheme", cascade = CascadeType.ALL)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Transaction> transactions;

    @OneToMany(mappedBy = "scheme", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TaxLot> taxLots = new ArrayList<>();

    @Column(name = "benchmark_index")
    private String benchmarkIndex;
}