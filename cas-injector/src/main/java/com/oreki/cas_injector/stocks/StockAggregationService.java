package com.oreki.cas_injector.stocks;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockAggregationService {
    private static final Logger log = LoggerFactory.getLogger(StockAggregationService.class);
    private final JdbcTemplate jdbc;

    public List<StockHoldingDTO> getPortfolio(String pan) {
        log.info("📊 Fetching stock portfolio summary for PAN: {}", pan);
        
        String sql = """
            WITH latest_prices AS (
                SELECT DISTINCT ON (ticker) ticker, close_price, price_date
                FROM stock_price_eod
                ORDER BY ticker, price_date DESC
            ),
            lot_summary AS (
                SELECT 
                    s.id as stock_id, s.ticker, s.isin, s.company_name, s.exchange, s.sector,
                    stl.remaining_qty,
                    stl.cost_basis_per_share,
                    stl.buy_date as purchase_date,
                    CASE 
                        WHEN stl.buy_date <= CURRENT_DATE - INTERVAL '365 days' THEN 'LTCG'
                        ELSE 'STCG'
                    END as category
                FROM stock s
                JOIN folio f ON s.folio_id = f.id
                JOIN stock_tax_lot stl ON s.id = stl.stock_id
                WHERE f.investor_pan = ? AND stl.status IN ('OPEN', 'PARTIAL')
            )
            SELECT 
                ls.ticker, ls.isin, ls.company_name, ls.exchange, ls.sector,
                SUM(ls.remaining_qty) as total_qty,
                SUM(ls.remaining_qty * ls.cost_basis_per_share) / NULLIF(SUM(ls.remaining_qty), 0) as avg_cost,
                COALESCE(lp.close_price, (SUM(ls.remaining_qty * ls.cost_basis_per_share) / NULLIF(SUM(ls.remaining_qty), 0))) as current_price,
                SUM(ls.remaining_qty * COALESCE(lp.close_price, ls.cost_basis_per_share)) as current_value,
                SUM(ls.remaining_qty * ls.cost_basis_per_share) as total_invested,
                SUM(CASE WHEN ls.category = 'LTCG' THEN ls.remaining_qty * (COALESCE(lp.close_price, ls.cost_basis_per_share) - ls.cost_basis_per_share) ELSE 0 END) as unrealised_ltcg,
                SUM(CASE WHEN ls.category = 'STCG' THEN ls.remaining_qty * (COALESCE(lp.close_price, ls.cost_basis_per_share) - ls.cost_basis_per_share) ELSE 0 END) as unrealised_stcg
            FROM lot_summary ls
            LEFT JOIN latest_prices lp ON ls.ticker = lp.ticker
            GROUP BY ls.ticker, ls.isin, ls.company_name, ls.exchange, ls.sector, lp.close_price
            """;

        return jdbc.query(sql, (rs, rowNum) -> {
            double currentVal = rs.getDouble("current_value");
            double invested   = rs.getDouble("total_invested");
            double uLtcg      = rs.getDouble("unrealised_ltcg");
            double uStcg      = rs.getDouble("unrealised_stcg");
            
            return StockHoldingDTO.builder()
                .ticker(rs.getString("ticker"))
                .isin(rs.getString("isin"))
                .companyName(rs.getString("company_name"))
                .exchange(rs.getString("exchange"))
                .sector(rs.getString("sector"))
                .quantity(rs.getDouble("total_qty"))
                .avgCostPerShare(rs.getDouble("avg_cost"))
                .currentPrice(rs.getDouble("current_price"))
                .currentValue(currentVal)
                .investedAmount(invested)
                .unrealisedPnl(currentVal - invested)
                .unrealisedPnlPct(invested > 0 ? (currentVal - invested) / invested * 100 : 0)
                .unrealisedLtcg(uLtcg)
                .unrealisedStcg(uStcg)
                .ltcgTaxEstimate(Math.max(0, uLtcg) * 0.125)
                .stcgTaxEstimate(Math.max(0, uStcg) * 0.20)
                .build();
        }, pan);
    }
}
