package com.oreki.cas_injector.core.repository;

import com.oreki.cas_injector.core.model.Scheme;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemeRepository extends JpaRepository<Scheme, Long> {

    Optional<Scheme> findByIsin(String isin);

    Optional<Scheme> findByNameIgnoreCase(String name);

    Optional<Scheme> findByName(String schemeName);
}
