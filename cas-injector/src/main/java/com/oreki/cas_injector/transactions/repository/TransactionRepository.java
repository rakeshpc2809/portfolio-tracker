package com.oreki.cas_injector.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oreki.cas_injector.transactions.model.Transaction;


@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
    boolean existsByTxnHash(String txnHash);
    java.util.Optional<Transaction> findByTxnHash(String txnHash);

    // Find all transactions of a specific type (e.g., "BUY")
    List<Transaction> findByTransactionType(String type);

    // Find everything EXCEPT Stamp Duty for your portfolio valuation
    List<Transaction> findByTransactionTypeNot(String type);

    long countBySchemeFolioInvestorPan(String pan);

    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.scheme.folio.investor.pan = :pan " +
           "AND UPPER(t.transactionType) != 'STAMP_DUTY'")
    long countActualTransactionsByPan(@Param("pan") String pan);

    @Query(value = "SELECT * FROM \"transaction\" t " +
           "WHERE t.scheme_id IN (" +
           "  SELECT s.id FROM scheme s " +
           "  JOIN folio f ON s.folio_id = f.id " +
           "  WHERE LTRIM(s.amfi_code, '0') = LTRIM(:amfiCode, '0') " +
           "  AND f.investor_pan = :pan" +
           ") " +
           "AND t.transaction_type = 'BUY' " +
           "ORDER BY t.transaction_date DESC LIMIT 1", nativeQuery = true)
    java.util.Optional<Transaction> findLatestBuyBySchemeAmfiCodeAndPan(@Param("amfiCode") String amfiCode, @Param("pan") String pan);

    @Query(value = "SELECT * FROM \"transaction\" t " +
           "WHERE t.scheme_id IN (" +
           "  SELECT s.id FROM scheme s " +
           "  JOIN folio f ON s.folio_id = f.id " +
           "  WHERE LTRIM(s.amfi_code, '0') = LTRIM(:amfiCode, '0') " +
           "  AND f.investor_pan = :pan" +
           ") " +
           "AND t.transaction_type = 'SELL' " +
           "ORDER BY t.transaction_date DESC LIMIT 1", nativeQuery = true)
    java.util.Optional<Transaction> findLatestSellBySchemeAmfiCodeAndPan(@Param("amfiCode") String amfiCode, @Param("pan") String pan);
}
