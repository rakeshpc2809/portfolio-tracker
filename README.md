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

1. **cas-injector (Java 21/Spring Boot)**: The primary engine. Features extreme performance through batch pre-fetching, shared NAV series caching, and complex native SQL window functions.
2. **cas-parser (Python FastAPI)**: Specialized sidecar for Hidden Markov Model (HMM) fitting and regime classification.
3. **portfolio-dashboard (React/Vite/TS)**: A "Material 3 Expressive" aesthetic UI utilizing Radix UI and Framer Motion. Visualizes Alpha Generation vs Benchmarks, Correction Timelines (Gantt), and a Capital Efficiency Matrix.

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
