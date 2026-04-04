package com.oreki.cas_injector.core.repository;
import com.oreki.cas_injector.core.model.Investor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorRepository extends JpaRepository<Investor, String> {
}
