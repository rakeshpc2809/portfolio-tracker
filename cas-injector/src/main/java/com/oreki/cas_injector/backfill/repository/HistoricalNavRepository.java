package com.oreki.cas_injector.backfill.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oreki.cas_injector.backfill.model.HistoricalNav;


public interface HistoricalNavRepository extends JpaRepository<HistoricalNav, Long> {

    List<HistoricalNav> findByAmfiCodeOrderByNavDateDesc(String amfiCode);
    
    Optional<HistoricalNav> findByAmfiCodeAndNavDate(String amfiCode, LocalDate navDate);
}
