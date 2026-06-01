package com.oreki.cas_injector.stocks;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.transactions.dto.TransactionDTO;

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

        List<StockHoldingDTO> portfolio = jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal totalQty     = rs.getBigDecimal("total_qty") != null ? rs.getBigDecimal("total_qty") : BigDecimal.ZERO;
            BigDecimal avgCost      = rs.getBigDecimal("avg_cost") != null ? rs.getBigDecimal("avg_cost") : BigDecimal.ZERO;
            BigDecimal currentPrice = rs.getBigDecimal("current_price") != null ? rs.getBigDecimal("current_price") : BigDecimal.ZERO;
            BigDecimal currentVal   = rs.getBigDecimal("current_value") != null ? rs.getBigDecimal("current_value") : BigDecimal.ZERO;
            BigDecimal invested    = rs.getBigDecimal("total_invested") != null ? rs.getBigDecimal("total_invested") : BigDecimal.ZERO;
            BigDecimal uLtcg       = rs.getBigDecimal("unrealised_ltcg") != null ? rs.getBigDecimal("unrealised_ltcg") : BigDecimal.ZERO;
            BigDecimal uStcg       = rs.getBigDecimal("unrealised_stcg") != null ? rs.getBigDecimal("unrealised_stcg") : BigDecimal.ZERO;
            
            BigDecimal unrealisedPnl = currentVal.subtract(invested);
            BigDecimal unrealisedPnlPct = BigDecimal.ZERO;
            if (invested.compareTo(BigDecimal.ZERO) > 0) {
                unrealisedPnlPct = unrealisedPnl.multiply(BigDecimal.valueOf(100)).divide(invested, 4, java.math.RoundingMode.HALF_UP);
            }
            
            BigDecimal ltcgTax = uLtcg.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(0.125));
            BigDecimal stcgTax = uStcg.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(0.20));
            
            return StockHoldingDTO.builder()
                .ticker(rs.getString("ticker"))
                .isin(rs.getString("isin"))
                .companyName(rs.getString("company_name"))
                .exchange(rs.getString("exchange"))
                .sector(rs.getString("sector"))
                .quantity(totalQty)
                .avgCostPerShare(avgCost)
                .currentPrice(currentPrice)
                .currentValue(currentVal)
                .investedAmount(invested)
                .unrealisedPnl(unrealisedPnl)
                .unrealisedPnlPct(unrealisedPnlPct)
                .unrealisedLtcg(uLtcg)
                .unrealisedStcg(uStcg)
                .ltcgTaxEstimate(ltcgTax)
                .stcgTaxEstimate(stcgTax)
                .build();
        }, pan);

        // Fetch all transactions to compute stock-level XIRR
        String txnSql = """
            SELECT s.ticker, st.transaction_date, st.transaction_type, st.total_amount
            FROM stock_transaction st
            JOIN stock s ON st.stock_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            ORDER BY st.transaction_date ASC
            """;

        Map<String, List<TransactionDTO>> stockTxns = new HashMap<>();
        jdbc.query(txnSql, (rs) -> {
            String ticker = rs.getString("ticker");
            java.sql.Date date = rs.getDate("transaction_date");
            String type = rs.getString("transaction_type");
            BigDecimal amount = rs.getBigDecimal("total_amount") != null ? rs.getBigDecimal("total_amount") : BigDecimal.ZERO;

            if (ticker != null && date != null && type != null) {
                BigDecimal flow = BigDecimal.ZERO;
                if ("BUY".equalsIgnoreCase(type)) {
                    flow = amount.negate();
                } else if ("SELL".equalsIgnoreCase(type)) {
                    flow = amount;
                } else {
                    return; // ignore non-cash events like splits/bonus for cash flow
                }
                stockTxns.computeIfAbsent(ticker, k -> new ArrayList<>())
                    .add(new TransactionDTO(flow, date.toLocalDate()));
            }
        }, pan);

        // Calculate XIRR for each stock
        for (StockHoldingDTO stock : portfolio) {
            List<TransactionDTO> txs = stockTxns.getOrDefault(stock.getTicker(), new ArrayList<>());
            List<TransactionDTO> cashFlows = new ArrayList<>(txs);
            if (stock.getQuantity() != null && stock.getQuantity().compareTo(BigDecimal.valueOf(0.0001)) > 0) {
                cashFlows.add(new TransactionDTO(stock.getCurrentValue(), LocalDate.now()));
            }
            
            BigDecimal xirr = BigDecimal.ZERO;
            if (cashFlows.size() >= 2) {
                try {
                    xirr = CommonUtils.SOLVE_XIRR.apply(cashFlows);
                } catch (Exception e) {
                    log.warn("Failed to calculate XIRR for stock {}: {}", stock.getTicker(), e.getMessage());
                }
            }
            stock.setXirr(xirr);
        }

        return portfolio;
    }
}
