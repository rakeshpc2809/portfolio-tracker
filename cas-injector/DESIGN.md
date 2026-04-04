# Design Document: cas-injector

The `cas-injector` is the core backend service of the Portfolio Tracker system, built using the Spring Boot framework.

## Responsibilities

1.  **Data Management**: Acts as the single source of truth for portfolio data, managing investors, folios, schemes, and transactions in a PostgreSQL database.
2.  **CAS Injection**: Provides an endpoint for receiving and processing parsed CAS data from the `cas-parser` service.
3.  **Market Climate Analysis**: Integrates with the `cas-scraper` to fetch market index fundamentals and calculates fund valuations based on benchmark PE/PB ratios.
4.  **Portfolio Analytics**: Computes portfolio metrics and provides a consolidated "Master Portfolio" view to the frontend.

## Core Components

### 1. `MarketClimateService`
This service handles the logic for synchronizing market climate data. It periodically triggers the `cas-scraper` (Python script) and processes index fundamentals to assess fund valuations.

- **Logic**: It compares current fund performance or category benchmarks against Nifty index fundamentals (PE/PB) to flag valuations as "OVERVALUED", "UNDERVALUED", or "N/A".

### 2. `CASInjectionController`
Exposes the API endpoint used by the `cas-parser` to upload parsed transaction data.

### 3. Data Models
- **`Investor`**: Stores user information like PAN and Email.
- **`Folio`**: Represents a specific folio number associated with an investor.
- **`Scheme`**: Represents a mutual fund scheme, including its AMFI code and asset category.
- **`Transaction`**: Records individual buy/sell/stamp duty events within a scheme.
- **`IndexFundamentals`**: Stores historical and current PE/PB data for market indices (NIFTY 50, NIFTY 500, etc.).

## Technology Stack

- **Framework**: Spring Boot 3.x
- **Persistence**: Spring Data JPA / Hibernate
- **Database**: PostgreSQL 16
- **Inter-service Communication**: REST APIs

## Design Patterns

- **Service Layer Pattern**: Decouples business logic from controllers.
- **Repository Pattern**: Abstracts data access logic.
- **DTO Pattern**: Used for transferring data between the backend and external services (frontend/parser).
