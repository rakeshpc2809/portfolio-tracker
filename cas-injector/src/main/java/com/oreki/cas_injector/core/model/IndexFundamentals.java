package com.oreki.cas_injector.core.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "index_fundamentals", 
       uniqueConstraints = {@UniqueConstraint(columnNames = {"index_name", "date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexFundamentals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "index_name")
    private String indexName;

    private LocalDate date;
    private Double pe;
    private Double pb;
    private Double divYield;
    @Column(name = "closing_price")
    private Double closingPrice;
}