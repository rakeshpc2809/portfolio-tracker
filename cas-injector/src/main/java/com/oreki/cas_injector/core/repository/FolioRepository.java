
package com.oreki.cas_injector.core.repository;

import com.oreki.cas_injector.core.model.Folio;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FolioRepository extends JpaRepository<Folio, Long> {

    Optional<Folio> findByFolioNumber(String folioNum);
}