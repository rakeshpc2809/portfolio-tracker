# Portfolio OS

A high-performance, intelligent portfolio tracking and rebalancing system designed to analyze Mutual Fund holdings (via CAS injections) and provide institutional-grade quantitative insights.

## System Capabilities

- **Automated Ingestion**: Seamlessly processes CAS PDF statements into a unified transaction ledger.
- **Advanced Quantitative Engine**:
    - **Regime Detection**: multi-scale Hurst Exponent (Anis-Lloyd corrected) and Hidden Markov Models (HMM) to distinguish between Trending, Mean-Reverting, and Random Walk markets.
    - **Risk Calibration**: Ornstein-Uhlenbeck (OU) process calibration for mean-reversion speed and optimal entry/exit thresholds.
    - **Tail-Risk Monitoring**: Real-time CVaR (Expected Shortfall) calculation with automatic "Circuit Breaker" buy suspension.
    - **Hierarchical Risk Parity (HRP)**: Optimized allocation using machine-learning clustering (Average Linkage/UPGMA) to maximize diversification.
- **Smart Rebalancing**:
    - **Mode 1: SIP Deployment**: Pure strategy-based monthly allocation.
    - **Mode 2: Opportunistic**: Conviction-weighted deployment of surplus capital (lumpsum).
    - **Mode 3: Exit Queue**: Strategic phasing out of "Dropped" funds, with built-in LTCG tax-shield overrides.
- **Fiscal Optimization**: 
    - Real-time Tax Loss Harvesting (TLH) opportunity scanning.
    - Unrealized gain tax projection (Exit Tax Estimate).
    - HIFO (Highest-In, First-Out) awareness for tax-efficient trimming.

## Technical Architecture

The system is a distributed microservices architecture:

1. **cas-injector (Java 21/Spring Boot)**: The primary engine. Features high-performance data processing through batch pre-fetching, shared NAV series caching, and optimized repository queries (`JOIN FETCH`).
2. **cas-parser (Python FastAPI)**: Specialized sidecar for Hidden Markov Model (HMM) fitting and high-density benchmark data scraping via Yahoo Finance.
3. **portfolio-dashboard (React/Vite/TS)**: A modern "Catppuccin Mocha" aesthetic UI utilizing Nivo charts and Framer Motion. Visualizes Alpha Generation vs Benchmarks, Allocation Heatmaps, and real-time Mean Reversion Pulse.

## Key Upgrades

- **Visual Overhaul**: Switched to the high-readability Catppuccin Mocha palette with expressive typography and consistent spatial design.
- **Benchmark Accuracy**: Replaced P/E-based proxies with true historical benchmark price series fetched via `yfinance`.
- **Quant Logic Hardening**: Ornstein-Uhlenbeck calibration now fits against log-price series for superior mean-reversion detection in mutual funds.
- **System Stability**: Resolved critical recursion issues (`StackOverflowError`) and JPA fetch collisions (`MultipleBagFetchException`) for large portfolio graphs.
- **Performance**: Centralized metric fetching and batch database operations ensure sub-200ms dashboard responsiveness.

## Running the Application

The entire stack is containerized with robust health-checks for synchronization.

```bash
docker compose up --build -d
```

- **Dashboard**: `http://localhost:80` (Internal port 80, mapped to host)
- **Backend API**: `http://localhost:8080/api`
- **Python Sidecar**: `http://localhost:8000`

## Developer Operations

- **Nightly Engine**: The Quantitative Engine runs nightly at 7 AM, recalculating all benchmarks, regimes, and rebalancing signals.
- **Data Integrity**: Global AMFI sanitization ensures consistent JOIN operations across internal and external data sources.
- **Performance**: Optimized to handle 100+ funds with sub-second response times through aggressive batching and L2 caching.
