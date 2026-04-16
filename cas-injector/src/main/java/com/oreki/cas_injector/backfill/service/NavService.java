package com.oreki.cas_injector.backfill.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.utils.CommonUtils;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, SchemeDetailsDTO> navCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("🚀 Warm-booting NAV Cache from database...");
        refreshCache();
    }

    public void refreshCache() {
        String sql = """
            WITH latest_navs AS (
                SELECT DISTINCT ON (amfi_code) amfi_code, nav, nav_date
                FROM fund_history
                ORDER BY amfi_code, nav_date DESC
            )
            SELECT l.amfi_code, l.nav, s.asset_category, s.name
            FROM latest_navs l
            LEFT JOIN scheme s ON LTRIM(l.amfi_code, '0') = LTRIM(s.amfi_code, '0')
        """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        int count = 0;
        for (Map<String, Object> row : rows) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) row.get("amfi_code"));
            BigDecimal nav = (row.get("nav") != null) ? new BigDecimal(row.get("nav").toString()) : BigDecimal.ZERO;
            String category = (String) row.get("asset_category");
            String name = (String) row.get("name");

            navCache.put(amfi, new SchemeDetailsDTO(nav, category != null ? category : "UNKNOWN", name != null ? name : "N/A"));
            count++;
        }
        log.info("✅ NAV Cache refreshed. {} funds loaded.", count);
    }

    public SchemeDetailsDTO getLatestSchemeDetails(String amfiCode) {
        String clean = CommonUtils.SANITIZE_AMFI.apply(amfiCode);
        return navCache.getOrDefault(clean, new SchemeDetailsDTO(BigDecimal.ZERO, "UNKNOWN", "N/A"));
    }
}
