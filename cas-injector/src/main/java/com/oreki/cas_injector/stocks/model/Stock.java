package com.oreki.cas_injector.stocks.model;

import com.oreki.cas_injector.core.model.Folio;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

import org.hibernate.annotations.SoftDelete;

@Entity
@Table(name = "stock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SoftDelete
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ticker;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Column(name = "security_id")
    private String securityId;

    @Column(unique = true)
    private String isin;

    @Builder.Default
    private String exchange = "NSE";

    @Column(name = "company_name")
    private String companyName;

    private String sector;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folio_id")
    private Folio folio;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
