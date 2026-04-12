package com.oreki.cas_injector.core.repository;

import com.oreki.cas_injector.core.model.IndexFundamentals;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface IndexFundamentalsRepository extends JpaRepository<IndexFundamentals, Long> {
    Optional<IndexFundamentals> findByIndexName(String indexName);
    Optional<IndexFundamentals> findByIndexNameAndDate(String indexName, LocalDate date);
    List<IndexFundamentals> findByIndexNameOrderByDateDesc(String indexName);
}