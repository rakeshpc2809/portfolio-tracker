package com.oreki.cas_injector.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.transactions.model.TaxLot;
public interface TaxLotRepository extends JpaRepository<TaxLot, Long> {
    // This MUST return Optional<TaxLot>
    Optional<TaxLot> findFirstBySchemeAndStatusOrderByBuyDateDesc(Scheme scheme, String status);
    
    List<TaxLot> findBySchemeAndStatusOrderByBuyDateAsc(Scheme scheme, String status);

    long countByStatus(String status);

    List<TaxLot> findByStatusAndSchemeFolioInvestorPan(String status,String investorPan);
}
