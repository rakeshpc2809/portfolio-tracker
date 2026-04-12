package com.oreki.cas_injector.transactions.service;

import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import com.oreki.cas_injector.dashboard.repository.PortfolioSummaryReadRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TransactionService {

    private final PortfolioSummaryReadRepository summaryRepository;

    public TransactionService(PortfolioSummaryReadRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    public Page<PortfolioSummary> getFilteredTransactions(String pan, String type, Pageable pageable) {
        return summaryRepository.findAll((Specification<PortfolioSummary>) (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 🧬 DIRECT LOOKUP on Materialized View (No joins needed!)
            if (pan != null && !pan.isEmpty()) {
                predicates.add(cb.equal(root.get("investorPan"), pan));
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
