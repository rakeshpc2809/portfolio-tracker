package com.oreki.cas_injector.stocks.repository;

import com.oreki.cas_injector.stocks.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByTickerAndExchange(String ticker, String exchange);
    Optional<Stock> findByIsin(String isin);
    List<Stock> findByFolioInvestorPan(String pan);
}
