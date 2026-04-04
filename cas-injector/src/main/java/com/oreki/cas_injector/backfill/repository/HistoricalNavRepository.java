package com.oreki.cas_injector.backfill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oreki.cas_injector.backfill.model.HistoricalNav;


public interface HistoricalNavRepository extends JpaRepository<HistoricalNav, Long> {

    List<HistoricalNav> findByAmfiCodeOrderByNavDateDesc(String amfiCode);
}
