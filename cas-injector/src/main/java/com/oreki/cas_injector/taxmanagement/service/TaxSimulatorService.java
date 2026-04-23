package com.oreki.cas_injector.taxmanagement.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.domain.port.TaxLotPort;
import com.oreki.cas_injector.domain.service.TaxSimulationDomainService;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.core.spec.TaxLotSpecs;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaxSimulatorService {

    private final TaxLotPort taxLotPort;
    private final TaxSimulationDomainService domainService;

    public TaxSimulatorService(TaxLotPort taxLotPort) {
        this.taxLotPort = taxLotPort;
        this.domainService = new TaxSimulationDomainService();
    }

    public TaxSimulationResult simulateSellOrder(String schemeName, double targetSellAmount, double currentNav, String investorPan) {
        log.info("🔍 [Hexagonal] Simulating HIFO Tax Friction for {} (PAN: {}) - Target: ₹{}", schemeName, investorPan, targetSellAmount);

        List<TaxLotDomain> openLots = taxLotPort.findOpenLotsBySchemeAndInvestor(schemeName, investorPan);
        double slabRate = taxLotPort.getInvestorSlabRate(investorPan);

        return domainService.calculateTaxFriction(openLots, targetSellAmount, currentNav, slabRate);
    }

    public TaxSimulationResult simulateHifoExit(List<TaxLot> lots, String category, double slabRate, double currentNav) {
        List<TaxLotDomain> domainLots = lots.stream()
            .map(this::toDomain)
            .toList();
        return domainService.simulateHifoExit(domainLots, category, slabRate, currentNav);
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
