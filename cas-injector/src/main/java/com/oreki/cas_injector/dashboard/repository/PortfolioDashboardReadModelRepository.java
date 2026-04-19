package com.oreki.cas_injector.dashboard.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.oreki.cas_injector.dashboard.model.PortfolioDashboardReadModel;

public interface PortfolioDashboardReadModelRepository extends JpaRepository<PortfolioDashboardReadModel, String> {
}
