# Project Codebase Index

This document provides a map of the Portfolio Tracker project structure to aid in navigation and debugging.

## Core Backend (`cas-injector/`)
*The central Spring Boot application managing data and metrics.*

- **`src/main/java/com/oreki/cas_injector/`**:
    - **`core/`**: Core entities (`Scheme`, `Investor`, `Folio`, `IndexFundamentals`), repositories, and DTOs.
    - **`convictionmetrics/`**: Logic for fund valuation and advanced metrics.
        - `service/MarketClimateService.java`: Orchestrates the market data sync and assessment.
        - `service/QuantitativeEngineService.java`: Calculates Sortino, CVaR, etc.
    - **`dashboard/`**: APIs and services for the frontend dashboard.
    - **`transactions/`**: Management of fund transactions and tax lots.
    - **`backfill/`**: Services for historical data synchronization and NAV updates.
- **`Dockerfile`**: Multistage build for Java 21 + Python environment.
- **`DESIGN.md`**: Backend architectural details.

## Parser Service (`cas-parser/`)
*Python FastAPI service for processing PDF CAS statements.*

- **`main.py`**: FastAPI entry point and parsing logic.
- **`requirements.txt`**: Python dependencies (casparser, fastapi, etc.).
- **`Dockerfile`**: Python slim-based container.
- **`DESIGN.md`**: Parser logic and data flow.

## Scraper Utility (`cas-scraper/`)
*Python utility for NSE market fundamentals.*

- **`scraper.py`**: Main script for fetching PE/PB ratios and updating the database.
- **`requirements.txt`**: Dependencies (nsepython, pandas, sqlalchemy).
- **`DESIGN.md`**: Market data acquisition logic.

## Frontend Dashboard (`portfolio-dashboard/`)
*React-based futuristic UI.*

- **`src/`**:
    - **`components/`**: UI components and layouts.
    - **`services/`**: API client for backend communication.
    - **`App.tsx`**: Main application state and entry.
- **`Dockerfile`**: Build and Nginx serve configuration.
- **`nginx.conf`**: Proxy settings for API routing.
- **`DESIGN.md`**: Frontend UI/UX philosophy.

## Infrastructure & Global
- **`docker-compose.yml`**: Full stack orchestration (Postgres, Backend, Parser, UI).
- **`README.md`**: System-wide documentation and setup.
- **`CODEBASE_INDEX.md`**: This file.
- **`logs/`**: Persistent logs directory.
- **`postgres_data/`**: Persistent database storage (ignored by Git/Index).

---
*Note: Build artifacts (`target/`, `dist/`), virtual environments (`venv/`), and dependency folders (`node_modules/`) are omitted from this index for clarity.*
