# Design Document: cas-scraper

The `cas-scraper` is a specialized Python utility for fetching market fundamentals from the National Stock Exchange (NSE).

## Responsibilities

1.  **Market Data Extraction**: Fetches PE, PB, and Dividend Yield data for key indices (NIFTY 50, NIFTY 500, etc.).
2.  **Database Synchronization**: Directly updates the `index_fundamentals` table in the PostgreSQL database.

## Core Logic

### 1. `nsepython` Integration
Utilizes the `index_pe_pb_div` function from the `nsepython` library to retrieve historical fundamentals for a specified date range.

### 2. Data Transformation
Uses `pandas` to:
-   Collect data for multiple target indices.
-   Normalize column names to match the database schema (e.g., lowercase and underscores).
-   Handle missing data or errors during the scraping process gracefully.

### 3. Direct SQL Upsert
Uses `sqlalchemy` to push the processed data to the database using `to_sql`.

## Technology Stack

-   **Language**: Python 3.10+
-   **Core Library**: `nsepython`
-   **Data Processing**: `pandas`
-   **Database Access**: `sqlalchemy`, `psycopg2`

## Key Indices Tracked

-   `NIFTY 50`
-   `NIFTY 500`
-   `NIFTY MIDCAP 150`
