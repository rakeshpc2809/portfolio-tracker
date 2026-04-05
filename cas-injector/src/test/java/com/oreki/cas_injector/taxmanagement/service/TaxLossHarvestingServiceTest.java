package com.oreki.cas_injector.taxmanagement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

@ExtendWith(MockitoExtension.class)
public class TaxLossHarvestingServiceTest {

    @Mock private TaxLotRepository taxLotRepository;
    @Mock private NavService amfiService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private TaxLossHarvestingService tlhService;

    @Test
    public void testScanForOpportunities_FindsLoss() {
        String pan = "PAN123";
        Scheme scheme = Scheme.builder().isin("ISIN1").amfiCode("100").name("Test Fund").build();
        
        // Buy 100 units at 100 each (Cost 10,000)
        TaxLot lot = TaxLot.builder()
            .scheme(scheme)
            .status("OPEN")
            .buyDate(LocalDate.now().minusMonths(6))
            .remainingUnits(new BigDecimal("100"))
            .costBasisPerUnit(new BigDecimal("100"))
            .build();

        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan))
            .thenReturn(Arrays.asList(lot));

        // Current NAV is 80 (Value 8,000, Loss 2,000)
        SchemeDetailsDTO details = new SchemeDetailsDTO();
        details.setNav(new BigDecimal("80"));
        when(amfiService.getLatestSchemeDetails("100")).thenReturn(details);

        List<TlhOpportunity> results = tlhService.scanForOpportunities(pan);

        assertEquals(1, results.size());
        assertEquals(2000.0, results.get(0).estimatedCapitalLoss());
        assertEquals(8000.0, results.get(0).harvestableAmount());
    }

    @Test
    public void testScanForOpportunities_IgnoresSmallLoss() {
        String pan = "PAN123";
        Scheme scheme = Scheme.builder().isin("ISIN1").amfiCode("100").name("Test Fund").build();
        
        // Loss of 500 (below 1000 threshold)
        TaxLot lot = TaxLot.builder()
            .scheme(scheme)
            .status("OPEN")
            .buyDate(LocalDate.now().minusMonths(6))
            .remainingUnits(new BigDecimal("100"))
            .costBasisPerUnit(new BigDecimal("100"))
            .build();

        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan))
            .thenReturn(Arrays.asList(lot));

        SchemeDetailsDTO details = new SchemeDetailsDTO();
        details.setNav(new BigDecimal("95")); // 5% drop, but only 500 total loss
        when(amfiService.getLatestSchemeDetails("100")).thenReturn(details);

        List<TlhOpportunity> results = tlhService.scanForOpportunities(pan);

        assertEquals(0, results.size());
    }
}
