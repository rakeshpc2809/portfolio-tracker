package com.oreki.cas_injector.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oreki.cas_injector.transactions.model.Transaction;


@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {    boolean existsByTxnHash(String txnHash);

    // Find all transactions of a specific type (e.g., "BUY")
    List<Transaction> findByTransactionType(String type);

    // Find everything EXCEPT Stamp Duty for your portfolio valuation
    List<Transaction> findByTransactionTypeNot(String type);

    long countBySchemeFolioInvestorPan(String pan);

    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.scheme.folio.investor.pan = :pan " +
           "AND UPPER(t.transactionType) != 'STAMP_DUTY'")
    long countActualTransactionsByPan(@Param("pan") String pan);
}