package com.oreki.cas_injector.infrastructure.adapter;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.domain.port.TaxLotPort;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JdbcTaxLotAdapter implements TaxLotPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<TaxLotDomain> findOpenLotsBySchemeAndInvestor(String schemeName, String investorPan) {
        String sql = """
                        SELECT
                            tl.id,
                            s.name,
                            tl.remaining_units,
                            tl.cost_basis_per_unit,
                            tl.buy_date,
                            s.asset_category
                        FROM tax_lot tl
                        JOIN scheme s ON tl.scheme_id = s.id
                        JOIN folio f ON s.folio_id = f.id
                        WHERE s.name = ?
                        AND f.investor_pan = ?
                        AND tl.remaining_units > 0
                        AND tl.status = 'OPEN'
                        ORDER BY tl.cost_basis_per_unit DESC
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> TaxLotDomain.builder()
            .id(rs.getLong("id"))
            .schemeName(rs.getString("name"))
            .remainingUnits(rs.getDouble("remaining_units"))
            .purchasePrice(rs.getDouble("cost_basis_per_unit"))
            .purchaseDate(rs.getDate("buy_date").toLocalDate())
            .assetCategory(rs.getString("asset_category"))
            .build(), schemeName, investorPan);
    }

    @Override
    public double getInvestorSlabRate(String investorPan) {
        Double slab = jdbcTemplate.queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, investorPan);
        return (slab != null) ? slab : 0.30;
    }
}
