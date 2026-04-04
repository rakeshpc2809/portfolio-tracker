package com.oreki.cas_injector.transactions.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.core.model.Folio;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public Page<Transaction> getFilteredTransactions(String pan, String type, Pageable pageable) {
        return transactionRepository.findAll((Specification<Transaction>) (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 🧬 DEEP JOIN: Transaction -> Scheme -> Folio -> Investor
            if (pan != null && !pan.isEmpty()) {
                Join<Transaction, Scheme> schemeJoin = root.join("scheme", JoinType.INNER);
                Join<Scheme, Folio> folioJoin = schemeJoin.join("folio", JoinType.INNER);
                Join<Folio, Investor> investorJoin = folioJoin.join("investor", JoinType.INNER);
                
                predicates.add(cb.equal(investorJoin.get("pan"), pan));
            }

            // 🏷️ FILTER: Transaction Type (BUY, SELL, etc.)
            if (type != null && !type.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(root.get("transactionType"), type.toUpperCase()));
            }

            // 🚫 EXCLUDE: Stamp Duty from main log by default if no type specified
            if (type == null || type.isEmpty() || type.equalsIgnoreCase("ALL")) {
                predicates.add(cb.notEqual(cb.upper(root.get("transactionType")), "STAMP_DUTY"));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        }, pageable);
    }
}