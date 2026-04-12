package com.oreki.cas_injector.dashboard.repository;

import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioSummaryReadRepository extends JpaRepository<PortfolioSummary, Long>, JpaSpecificationExecutor<PortfolioSummary> {
}
