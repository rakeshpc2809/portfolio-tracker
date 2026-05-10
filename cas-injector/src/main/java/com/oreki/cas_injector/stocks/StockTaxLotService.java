package com.oreki.cas_injector.stocks;

import com.oreki.cas_injector.stocks.model.Stock;
import com.oreki.cas_injector.stocks.model.StockTaxLot;
import com.oreki.cas_injector.stocks.model.StockTransaction;
import com.oreki.cas_injector.stocks.repository.StockTaxLotRepository;
import com.oreki.cas_injector.stocks.repository.StockTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StockTaxLotService {
    private final StockTaxLotRepository lotRepo;
    private final StockTransactionRepository txnRepo;

    @Transactional
    public void rebuildLotsForStock(Stock stock) {
        log.info("♻️ Rebuilding FIFO tax lots for stock: {}", stock.getTicker());
        
        // Clear existing lots for this stock
        lotRepo.deleteByStock(stock);

        // Fetch all transactions sorted by date
        List<StockTransaction> txns = txnRepo.findByStockOrderByTransactionDateAscIdAsc(stock);

        for (StockTransaction tx : txns) {
            String type = tx.getTransactionType().toUpperCase();
            if (type.equals("BUY")) {
                processBuy(tx);
            } else if (type.equals("SELL")) {
                processSell(tx);
            }
        }
    }

    private void processBuy(StockTransaction tx) {
        lotRepo.save(StockTaxLot.builder()
                .stock(tx.getStock())
                .buyTransaction(tx)
                .buyDate(tx.getTransactionDate())
                .originalQty(tx.getQuantity())
                .remainingQty(tx.getQuantity())
                .costBasisPerShare(tx.getPricePerShare())
                .status("OPEN")
                .build());
    }

    private void processSell(StockTransaction sellTx) {
        BigDecimal qtyToConsume = sellTx.getQuantity();
        List<StockTaxLot> openLots = lotRepo.findByStockAndStatusInOrderByBuyDateAsc(sellTx.getStock(), List.of("OPEN", "PARTIAL"));

        for (StockTaxLot lot : openLots) {
            if (qtyToConsume.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal consumed = qtyToConsume.min(lot.getRemainingQty());
            lot.setRemainingQty(lot.getRemainingQty().subtract(consumed));
            qtyToConsume = qtyToConsume.subtract(consumed);

            if (lot.getRemainingQty().compareTo(BigDecimal.ZERO) <= 0) {
                lot.setStatus("CLOSED");
            } else {
                lot.setStatus("PARTIAL");
            }
            lotRepo.save(lot);
        }
        
        if (qtyToConsume.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("⚠️ Negative inventory for stock {}: {} units short", sellTx.getStock().getTicker(), qtyToConsume);
        }
    }
}
