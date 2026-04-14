package com.oreki.cas_injector.transactions.service;

import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TransactionService {

    private final JdbcTemplate jdbcTemplate;

    public TransactionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Page<PortfolioSummary> getFilteredTransactions(String pan, String type, Pageable pageable) {
        StringBuilder sql = new StringBuilder("SELECT * FROM mv_portfolio_summary WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (pan != null && !pan.isEmpty()) {
            sql.append(" AND investor_pan = ?");
            params.add(pan);
        }

        if (type != null && !type.equalsIgnoreCase("ALL")) {
            sql.append(" AND UPPER(transaction_type) = ?");
            params.add(type.toUpperCase());
        } else {
            sql.append(" AND UPPER(transaction_type) != 'STAMP_DUTY'");
        }

        // Handle Sorting
        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            sql.append(" ORDER BY ");
            sort.forEach(order -> {
                String property = order.getProperty();
                // Map Java properties to SQL columns
                String column = switch (property) {
                    case "transactionDate" -> "transaction_date";
                    case "amount" -> "amount";
                    case "units" -> "units";
                    case "schemeName" -> "scheme_name";
                    default -> "transaction_date";
                };
                sql.append(column).append(" ").append(order.getDirection().name()).append(", ");
            });
            sql.setLength(sql.length() - 2); // Remove trailing comma
        }

        // Handle Pagination
        String countSql = "SELECT COUNT(*) FROM (" + sql.toString() + ") AS count_query";
        Long total = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);

        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageable.getPageSize());
        params.add(pageable.getOffset());

        List<PortfolioSummary> content = jdbcTemplate.query(
                sql.toString(),
                new BeanPropertyRowMapper<>(PortfolioSummary.class),
                params.toArray()
        );

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
