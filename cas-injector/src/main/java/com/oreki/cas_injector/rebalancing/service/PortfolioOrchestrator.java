package com.oreki.cas_injector.rebalancing.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.rebalancing.dto.PortfolioStateItemDTO;
import com.oreki.cas_injector.rebalancing.dto.TaxHeadroomDTO;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioOrchestrator {

    private final TaxLotRepository taxLotRepository;
    private final NavService amfiService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Calculates the tax headroom for a specific investor.
     * Capped at ₹1.25L.
     */
    public TaxHeadroomDTO calculateTaxHeadroom(String pan) {
        log.info("🧮 Calculating strict Tax Headroom for PAN: {}", pan);

        // Fetch FY Realized LTCG
        BigDecimal realizedLtcg = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(cg.realized_gain), 0)
            FROM capital_gain_audit cg
            JOIN "transaction" t ON cg.sell_transaction_id = t.id
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND cg.tax_category IN ('EQUITY_LTCG', 'HYBRID_LTCG', 'NON_EQUITY_LTCG_OLD')
            AND t.transaction_date >= ?
            """,
            BigDecimal.class, pan, CommonUtils.getCurrentFyStart());

        if (realizedLtcg == null) {
            realizedLtcg = BigDecimal.ZERO;
        }

        // Limit is ₹1.25L (₹125,000)
        BigDecimal limit = new BigDecimal("125000");
        BigDecimal headroomRemaining = limit.subtract(realizedLtcg).max(BigDecimal.ZERO).min(limit);

        // Scan OPEN lots and filter for age > 365 days
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        LocalDate currentDate = LocalDate.now();

        BigDecimal totalUnrealizedLtcg = BigDecimal.ZERO;
        BigDecimal harvestableLtcg = BigDecimal.ZERO;

        for (TaxLot lot : openLots) {
            long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), currentDate);
            if (daysHeld > 365) {
                BigDecimal currentNav = amfiService.getLatestSchemeDetails(lot.getScheme().getAmfiCode()).getNav();
                if (currentNav == null) {
                    currentNav = BigDecimal.ZERO;
                }

                BigDecimal costBasis = lot.getCostBasisPerUnit();
                if (costBasis == null) {
                    costBasis = BigDecimal.ZERO;
                }

                BigDecimal remainingUnits = lot.getRemainingUnits();
                if (remainingUnits == null) {
                    remainingUnits = BigDecimal.ZERO;
                }

                BigDecimal lotGain = currentNav.subtract(costBasis).multiply(remainingUnits);
                totalUnrealizedLtcg = totalUnrealizedLtcg.add(lotGain);

                if (lotGain.compareTo(BigDecimal.ZERO) > 0) {
                    harvestableLtcg = harvestableLtcg.add(lotGain);
                }
            }
        }

        BigDecimal harvestableLtcgCapped = harvestableLtcg.min(headroomRemaining);

        return TaxHeadroomDTO.builder()
            .ltcgRealizedThisFy(realizedLtcg)
            .ltcgHeadroomRemaining(headroomRemaining)
            .totalUnrealizedLtcg(totalUnrealizedLtcg)
            .harvestableLtcg(harvestableLtcgCapped)
            .build();
    }

    /**
     * Returns current unit balances grouped by Scheme alongside locked STCG and harvestable LTCG units.
     */
    public List<PortfolioStateItemDTO> getPortfolioState(String pan) {
        log.info("📊 Fetching strict Portfolio State for PAN: {}", pan);

        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        Map<Scheme, List<TaxLot>> lotsByScheme = openLots.stream()
            .collect(Collectors.groupingBy(TaxLot::getScheme));

        List<PortfolioStateItemDTO> stateItems = new ArrayList<>();
        LocalDate currentDate = LocalDate.now();

        for (Map.Entry<Scheme, List<TaxLot>> entry : lotsByScheme.entrySet()) {
            Scheme scheme = entry.getKey();
            List<TaxLot> schemeLots = entry.getValue();

            BigDecimal totalUnits = BigDecimal.ZERO;
            BigDecimal lockedStcgUnits = BigDecimal.ZERO;
            BigDecimal totalLtcgUnits = BigDecimal.ZERO;
            BigDecimal harvestableLtcgUnits = BigDecimal.ZERO;

            BigDecimal currentNav = amfiService.getLatestSchemeDetails(scheme.getAmfiCode()).getNav();
            if (currentNav == null) {
                currentNav = BigDecimal.ZERO;
            }

            for (TaxLot lot : schemeLots) {
                BigDecimal remainingUnits = lot.getRemainingUnits();
                if (remainingUnits == null) {
                    remainingUnits = BigDecimal.ZERO;
                }
                totalUnits = totalUnits.add(remainingUnits);

                long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), currentDate);
                if (daysHeld <= 365) {
                    lockedStcgUnits = lockedStcgUnits.add(remainingUnits);
                } else {
                    totalLtcgUnits = totalLtcgUnits.add(remainingUnits);
                    
                    BigDecimal costBasis = lot.getCostBasisPerUnit();
                    if (costBasis == null) {
                        costBasis = BigDecimal.ZERO;
                    }
                    if (currentNav.compareTo(costBasis) > 0) {
                        harvestableLtcgUnits = harvestableLtcgUnits.add(remainingUnits);
                    }
                }
            }

            stateItems.add(PortfolioStateItemDTO.builder()
                .schemeName(scheme.getName())
                .amfiCode(scheme.getAmfiCode())
                .isin(scheme.getIsin())
                .totalUnits(totalUnits)
                .lockedStcgUnits(lockedStcgUnits)
                .totalLtcgUnits(totalLtcgUnits)
                .harvestableLtcgUnits(harvestableLtcgUnits)
                .build());
        }

        return stateItems;
    }
}
