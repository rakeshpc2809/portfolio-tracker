package com.oreki.cas_injector.stocks.repository;

import com.oreki.cas_injector.stocks.model.StockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {
    List<StockTransaction> findByStockIdOrderByTransactionDateAsc(Long stockId);
    List<StockTransaction> findByStockOrderByTransactionDateAscIdAsc(com.oreki.cas_injector.stocks.model.Stock stock);
    void deleteByStock(com.oreki.cas_injector.stocks.model.Stock stock);
}
