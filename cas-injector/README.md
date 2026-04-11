# cas-injector (Java Backend)

The central brain of Portfolio OS. Built with Java 25 and Spring Boot.

## Core Responsibilities

1. **CAS Ingestion & Parsing**: Integrates with external or internal parsers to read PDF statements and map them to a local SQL database (`fund_history`, `transaction`, `folio`, `tax_lot`).
2. **Quantitative Math Engine**: A nightly scheduled job (`QuantitativeEngineService`) orchestrates a sequence of advanced calculations:
   - **Risk Metrics**: Sortino Ratio, CVaR (5%), Maximum Drawdown, Win Rate.
   - **Relative Scoring**: Computes a Composite Quant Score (CQS) by grouping funds into asset-class buckets and Z-scoring their risk metrics against peers.
   - **Volatility Tax & Hurst Exponent**: Analyzes 252-day rolling returns to calculate variance drag and R/S fractal dimensionality (trending vs. mean-reverting).
   - **Ornstein-Uhlenbeck (OU) Calibration**: Models fund NAVs as an AR(1) process to calculate half-life, mean reversion speed (theta), and statistical bounds for "Rubber Band" signals.
   - **HMM Regime Filter**: Delegates computation to `cas-parser` to classify market regimes.
3. **Portfolio Orchestrator**: Evaluates holdings against a defined Strategy (Google Sheets or local DB) and generates `TacticalSignal`s across three modes:
   - *Mode 1 (SIP)*: Directs monthly cash flows, pausing or redirecting if a fund is overheated.
   - *Mode 2 (Opportunistic)*: Identifies extreme statistical discounts using the math engine to deploy lump sums safely.
   - *Mode 3 (Exit)*: Manages the orderly winding down of dropped funds, optimizing for Long-Term Capital Gains (LTCG) tax efficiency.
4. **Hierarchical Risk Parity (HRP)**: Clusters funds by correlation to detect hidden concentration risks (HERC) and adjust optimal portfolio weights.

## Technology Stack
- Java 25
- Spring Boot 3.x
- Spring Data JDBC / JPA
- PostgreSQL
