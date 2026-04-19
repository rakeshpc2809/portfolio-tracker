package com.oreki.cas_injector.domain.model;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaxLotDomain {
    private Long id;
    private String schemeName;
    private double remainingUnits;
    private double purchasePrice;
    private LocalDate purchaseDate;
    private String assetCategory;
}
