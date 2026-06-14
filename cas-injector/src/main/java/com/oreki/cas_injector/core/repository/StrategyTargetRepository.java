package com.oreki.cas_injector.core.repository;

import com.oreki.cas_injector.core.model.StrategyTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface StrategyTargetRepository extends JpaRepository<StrategyTarget, Long> {
    Optional<StrategyTarget> findByInvestorPanAndAmfiCode(String investorPan, String amfiCode);
    List<StrategyTarget> findByInvestorPan(String investorPan);
}

