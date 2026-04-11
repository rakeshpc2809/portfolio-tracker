# Portfolio OS

A comprehensive, intelligent portfolio tracking and rebalancing system designed to analyze Mutual Fund holdings (via CAS injections) and provide actionable, data-driven insights.

## Architecture Overview

The system is composed of three primary microservices:

1. **cas-injector (Java Spring Boot)**: The core backend engine. It ingests CAS PDFs, processes transactions, and runs a sophisticated quantitative math engine (Sortino, CVaR, Multi-scale Hurst, Ornstein-Uhlenbeck (OU) Mean Reversion, Hierarchical Risk Parity). It orchestrates three modes of rebalancing (SIP Deployment, Opportunistic Accumulation, Exit Queue) and serves REST APIs.
2. **cas-parser (Python FastAPI)**: A lightweight Python sidecar responsible for tasks requiring specialized Python libraries. Primarily, it uses `hmmlearn` to fit Hidden Markov Models (HMM) on historical NAV data, identifying underlying market regimes (Volatile Bear, Stressed Neutral, Calm Bull).
3. **portfolio-dashboard (React/Vite)**: A modern, aesthetic frontend built with React, Vite, Tailwind CSS, and Radix UI primitives. It visualizes the portfolio's "Vital Signs" (CVaR, Tax Efficiency, Regime Pulse, Average Reversion Speed) and provides an interactive ledger and fund-level breakdown with detailed Explanation Engine tooltips.

## Running the Application

The entire stack is containerized and orchestrated via Docker Compose.

```bash
docker compose up --build -d
```

- **Dashboard**: `http://localhost:5173`
- **Backend API**: `http://localhost:8080`
- **Python Sidecar**: `http://localhost:8000`
