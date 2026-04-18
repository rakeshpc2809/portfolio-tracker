package com.oreki.cas_injector.taxmanagement.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LtcgExitSchedulerService {

    private final TaxLotRepository taxLotRepository;
    private final NavService navService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private static final double LTCG_ANNUAL_EXEMPTION = 125000.0;

    public record ExitScheduleItem(
        String  schemeName,
        double  currentValue,
        double  ltcgGains,
        double  stcgGains,
        int     daysToLtcgConversion,
        String  suggestedFY,         // "CURRENT_FY" or "NEXT_FY"
        double  taxIfThisFY,
        double  taxIfNextFY,
        double  saving,
        String  reason
    ) {}

    public List<ExitScheduleItem> computeOptimalExitSchedule(
            String pan,
            double fyLtcgAlreadyRealized,
            List<String> droppedIsins) {

        double remainingThisFY = Math.max(0, LTCG_ANNUAL_EXEMPTION - fyLtcgAlreadyRealized);
        double capacityNextFY  = LTCG_ANNUAL_EXEMPTION; 

        Double slab = jdbcTemplate.queryForObject("SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        double slabRate = (slab != null) ? slab : 0.30;

        List<ExitScheduleItem> schedule = new ArrayList<>();

        // Fetch lots for dropped funds
        List<TaxLot> lots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan)
            .stream()
            .filter(l -> droppedIsins.contains(l.getScheme().getIsin()))
            .collect(Collectors.toList());

        // Group by scheme
        Map<String, List<TaxLot>> byScheme = lots.stream()
            .collect(Collectors.groupingBy(l -> l.getScheme().getName()));

        for (var entry : byScheme.entrySet()) {
            String name = entry.getKey();
            List<TaxLot> fundLots = entry.getValue();

            String amfiCode = fundLots.get(0).getScheme().getAmfiCode();
            var details = navService.getLatestSchemeDetails(amfiCode);
            double currentNav = details.getNav().doubleValue();
            String category = (details.getCategory() != null) ? details.getCategory().toUpperCase() : "OTHER";

            double ltcgG = 0, stcgG = 0, slabG = 0, totalVal = 0;
            int minDays = 365;
            boolean hasEquityStcg = false;
            boolean isSlabFund = false;

            for (TaxLot lot : fundLots) {
                double units = lot.getRemainingUnits().doubleValue();
                double cost  = lot.getCostBasisPerUnit().doubleValue() * units;
                double val   = units * currentNav;
                double gain  = Math.max(0, val - cost);
                totalVal += val;

                String taxCat = com.oreki.cas_injector.core.utils.CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), category);
                if (taxCat.contains("LTCG")) {
                    ltcgG += gain;
                } else if (taxCat.equals("SLAB_RATE_TAX")) {
                    slabG += gain;
                    isSlabFund = true;
                } else {
                    stcgG += gain;
                    long age = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                    int daysLeft = (int)(365 - age);
                    if (daysLeft < minDays) minDays = daysLeft;
                    hasEquityStcg = true;
                }
            }

            double taxThisFY;
            double taxNextFY;

            if (isSlabFund) {
                // Slab funds: simple multiplication, no exemption
                taxThisFY = (ltcgG + stcgG + slabG) * slabRate;
                taxNextFY = taxThisFY; // Assume slab rate stays same
            } else {
                // LTCG eligible funds
                double taxableLtcgThisFY = Math.max(0, ltcgG - remainingThisFY);
                taxThisFY = taxableLtcgThisFY * 0.125 + stcgG * 0.20;

                if (hasEquityStcg && minDays < 120) {
                    double totalLtcgNextFY = ltcgG + stcgG;
                    double taxableNextFY = Math.max(0, totalLtcgNextFY - capacityNextFY);
                    taxNextFY = taxableNextFY * 0.125;
                } else {
                    double taxableNextFY = Math.max(0, ltcgG - capacityNextFY);
                    taxNextFY = taxableNextFY * 0.125 + stcgG * 0.20;
                }
            }

            String suggested = taxNextFY < taxThisFY - 500 ? "NEXT_FY" : "CURRENT_FY";
            double saving = Math.max(0, taxThisFY - taxNextFY);

            String reason;
            if (isSlabFund) {
                reason = "Debt fund: Taxed at slab rate. Exit whenever liquidity is needed.";
            } else {
                reason = switch (suggested) {
                    case "NEXT_FY" -> String.format("Deferring to next FY saves ₹%.0f. LTCG budget resets April 1.", saving);
                    default -> ltcgG <= remainingThisFY
                        ? "Exit this FY — gains fit within remaining ₹" + Math.round(remainingThisFY) + " exemption."
                        : "Exit this FY — deferral saves less than ₹500.";
                };
            }

            schedule.add(new ExitScheduleItem(
                name, totalVal, ltcgG, stcgG + slabG,
                !isSlabFund && hasEquityStcg ? minDays : 0,
                suggested, taxThisFY, taxNextFY, saving, reason
            ));

            if ("CURRENT_FY".equals(suggested) && !isSlabFund) {
                remainingThisFY = Math.max(0, remainingThisFY - ltcgG);
            }
        }

        // Sort: tax-free exits first, then by saving descending
        schedule.sort(Comparator
            .comparingDouble((ExitScheduleItem e) -> e.taxIfThisFY())
            .thenComparingDouble(e -> -e.saving()));

        return schedule;
    }
}
