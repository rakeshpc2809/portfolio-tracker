package com.oreki.cas_injector.goals.repository;

import com.oreki.cas_injector.goals.model.PortfolioGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GoalRepository extends JpaRepository<PortfolioGoal, Long> {
    List<PortfolioGoal> findByInvestorPan(String investorPan);
}
