package com.oreki.cas_injector.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.transactions.model.TaxLot;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaxLotRepository extends JpaRepository<TaxLot, Long> {
    // This MUST return Optional<TaxLot>
    Optional<TaxLot> findFirstBySchemeAndStatusOrderByBuyDateDesc(Scheme scheme, String status);
    
    List<TaxLot> findBySchemeAndStatusOrderByBuyDateAsc(Scheme scheme, String status);

    long countByStatus(String status);

    long countByStatusAndSchemeFolioInvestorPan(String status, String investorPan);

    @Query("SELECT tl FROM TaxLot tl JOIN FETCH tl.scheme WHERE tl.status = :status AND tl.scheme.folio.investor.pan = :pan")
    List<TaxLot> findByStatusAndSchemeFolioInvestorPan(@Param("status") String status, @Param("pan") String pan);
}
