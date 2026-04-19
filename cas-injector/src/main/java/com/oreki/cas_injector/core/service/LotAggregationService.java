package com.oreki.cas_injector.core.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.domain.service.AggregationDomainService;
import com.oreki.cas_injector.transactions.model.TaxLot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LotAggregationService {

    private final NavService amfiService;
    private final AggregationDomainService domainService = new AggregationDomainService();

    public List<AggregatedHolding> aggregate(List<TaxLot> lots) {
        Map<Scheme, List<TaxLot>> groupedLots = lots.stream()
            .collect(Collectors.groupingBy(TaxLot::getScheme));

        return groupedLots.entrySet().stream().map(entry -> {
            Scheme scheme = entry.getKey();
            SchemeDetailsDTO details = amfiService.getLatestSchemeDetails(scheme.getAmfiCode());
            double liveNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
            String category = scheme.getAssetCategory() != null ? scheme.getAssetCategory() : (details != null ? details.getCategory() : "");

            List<TaxLotDomain> domainLots = entry.getValue().stream()
                .map(this::toDomain)
                .toList();

            return domainService.aggregateSchemeLots(scheme.getName(), scheme.getIsin(), category, liveNav, domainLots);
        }).collect(Collectors.toList());
    }

    private TaxLotDomain toDomain(TaxLot lot) {
        return TaxLotDomain.builder()
            .id(lot.getId())
            .schemeName(lot.getScheme().getName())
            .remainingUnits(lot.getRemainingUnits().doubleValue())
            .purchasePrice(lot.getCostBasisPerUnit().doubleValue())
            .purchaseDate(lot.getBuyDate())
            .assetCategory(lot.getScheme().getAssetCategory())
            .build();
    }
}
