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
        double capacityNextFY  = LTCG_ANNUAL_EXEMPTION; // Resets on April 1

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

            double currentNav = navService.getLatestSchemeDetails(
                fundLots.get(0).getScheme().getAmfiCode()).getNav().doubleValue();

            double ltcgG = 0, stcgG = 0, totalVal = 0;
            int minDays = 365;
            boolean hasStcg = false;

            for (TaxLot lot : fundLots) {
                double units = lot.getRemainingUnits().doubleValue();
                double cost  = lot.getCostBasisPerUnit().doubleValue() * units;
                double val   = units * currentNav;
                double gain  = val - cost;
                long   age   = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                totalVal += val;
                if (age >= 365) {
                    ltcgG += Math.max(0, gain);
                } else {
                    stcgG += Math.max(0, gain);
                    int daysLeft = (int)(365 - age);
                    if (daysLeft < minDays) minDays = daysLeft;
                    hasStcg = true;
                }
            }

            // Tax this FY
            double taxableLtcgThisFY = Math.max(0, ltcgG - remainingThisFY);
            double taxThisFY = taxableLtcgThisFY * 0.125 + stcgG * 0.20;

            // Tax next FY (assume STCG converts to LTCG if we wait minDays)
            double taxNextFY;
            if (hasStcg && minDays < 120) {
                // All gains become LTCG next FY
                double totalLtcgNextFY = ltcgG + stcgG;
                double taxableNextFY = Math.max(0, totalLtcgNextFY - capacityNextFY);
                taxNextFY = taxableNextFY * 0.125;
            } else {
                double taxableNextFY = Math.max(0, ltcgG - capacityNextFY);
                taxNextFY = taxableNextFY * 0.125 + stcgG * 0.20;
            }

            String suggested = taxNextFY < taxThisFY - 500 ? "NEXT_FY" : "CURRENT_FY";
            double saving = Math.max(0, taxThisFY - taxNextFY);

            String reason = switch (suggested) {
                case "NEXT_FY" -> String.format(
                    "Deferring to next FY saves ₹%.0f. LTCG budget resets April 1.", saving);
                default -> ltcgG <= remainingThisFY
                    ? "Exit this FY — gains fit within remaining ₹" + Math.round(remainingThisFY) + " exemption."
                    : "Exit this FY — deferral saves less than ₹500.";
            };

            schedule.add(new ExitScheduleItem(
                name, totalVal, ltcgG, stcgG,
                hasStcg ? minDays : 0,
                suggested, taxThisFY, taxNextFY, saving, reason
            ));

            // Consume exemption budget if exiting this FY
            if ("CURRENT_FY".equals(suggested)) {
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
