package com.oreki.cas_injector.stocks.repository;

import com.oreki.cas_injector.stocks.model.StockPriceEod;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface StockPriceEodRepository extends JpaRepository<StockPriceEod, StockPriceEod.StockPriceId> {
    Optional<StockPriceEod> findFirstByTickerOrderByPriceDateDesc(String ticker);
}
