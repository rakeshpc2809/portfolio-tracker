package com.oreki.cas_injector.stocks.repository;

import com.oreki.cas_injector.stocks.model.StockTaxLot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StockTaxLotRepository extends JpaRepository<StockTaxLot, Long> {
    List<StockTaxLot> findByStockIdAndStatusInOrderByBuyDateAsc(Long stockId, List<String> statuses);
    List<StockTaxLot> findByStockAndStatusInOrderByBuyDateAsc(com.oreki.cas_injector.stocks.model.Stock stock, List<String> statuses);
    List<StockTaxLot> findByStockFolioInvestorPanAndStatusIn(String pan, List<String> statuses);
    void deleteByStock(com.oreki.cas_injector.stocks.model.Stock stock);
}
