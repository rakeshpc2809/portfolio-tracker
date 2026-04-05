package com.oreki.cas_injector.transactions.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oreki.cas_injector.transactions.model.CapitalGainAudit;

public interface CapitalGainAuditRepository extends JpaRepository<CapitalGainAudit, Long> {
    @Query("SELECT SUM(a.realizedGain) FROM CapitalGainAudit a " +
           "WHERE a.sellTransaction.scheme.folio.investor.pan = :pan")
    BigDecimal sumRealizedGainByPan(@Param("pan") String pan);

    List<CapitalGainAudit> findAllBySellTransactionSchemeFolioInvestorPan(String pan);

    List<CapitalGainAudit> findAllBySellTransactionSchemeId(Long schemeId);
}