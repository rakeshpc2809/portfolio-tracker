package com.oreki.cas_injector.core.repository;
import com.oreki.cas_injector.core.model.Investor;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface InvestorRepository extends JpaRepository<Investor, String> {

    @Query("SELECT i FROM Investor i " +
           "LEFT JOIN FETCH i.folios f " +
           "LEFT JOIN FETCH f.schemes s " +
           "WHERE i.pan = :pan")
    Optional<Investor> findByPanWithDetails(@Param("pan") String pan);
}
