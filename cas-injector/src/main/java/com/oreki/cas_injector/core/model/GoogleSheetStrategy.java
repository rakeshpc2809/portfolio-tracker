package com.oreki.cas_injector.core.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "google_sheet_strategy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleSheetStrategy {

    @Id
    private String isin;

    private String schemeName;
    private Double targetPortfolioPct;
    private Double sipPct;
    private String status;
    private String bucket;
}
