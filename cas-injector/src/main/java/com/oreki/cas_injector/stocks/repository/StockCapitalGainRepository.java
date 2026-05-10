package com.oreki.cas_injector.stocks.repository;

import com.oreki.cas_injector.stocks.model.Stock;
import com.oreki.cas_injector.stocks.model.StockCapitalGain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockCapitalGainRepository extends JpaRepository<StockCapitalGain, Long> {
    void deleteByStock(Stock stock);
}
