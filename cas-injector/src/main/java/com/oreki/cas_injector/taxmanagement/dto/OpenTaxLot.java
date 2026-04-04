package com.oreki.cas_injector.taxmanagement.dto;

import java.time.LocalDate;

public record OpenTaxLot(
    Long id,
    String schemeName,
    double remainingUnits,
    double purchasePrice,
    LocalDate purchaseDate,
    String assetCategory // "EQUITY" or "DEBT"
) {}