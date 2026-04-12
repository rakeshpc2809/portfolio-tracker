package com.oreki.cas_injector.backfill.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oreki.cas_injector.backfill.model.HistoricalNav;


public interface HistoricalNavRepository extends JpaRepository<HistoricalNav, Long> {

    List<HistoricalNav> findByAmfiCodeOrderByNavDateDesc(String amfiCode);
    
    Optional<HistoricalNav> findByAmfiCodeAndNavDate(String amfiCode, LocalDate navDate);

    @Query("SELECT h FROM HistoricalNav h WHERE h.amfiCode IN :amfiCodes AND h.navDate IN :dates")
    List<HistoricalNav> findByAmfiCodeInAndNavDateIn(@Param("amfiCodes") Collection<String> amfiCodes, @Param("dates") Collection<LocalDate> dates);
}
