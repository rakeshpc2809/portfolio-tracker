package com.oreki.cas_injector.core.model;

import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Investor {
    @Id
    private String pan; // Acts as the unique Primary Key
    
    private String name;
    private String email;

    @Builder.Default
    private Double taxSlab = 0.30; // Default 30% slab

    // Mapping to multiple Folios
    @OneToMany(mappedBy = "investor", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude // Prevents circular dependency in logs
    @EqualsAndHashCode.Exclude // Prevents circular dependency
    private Set<Folio> folios;
}