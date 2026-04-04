# Portfolio Tracker

A comprehensive portfolio management system designed to track, analyze, and visualize mutual fund investments using Consolidated Account Statements (CAS).

## System Architecture

The project is built as a microservices-based system, containerized with Docker, comprising the following components:

1.  **`cas-injector` (Java Backend)**: The central brain of the system, built with Spring Boot. It manages the core logic, data persistence in PostgreSQL, and exposes APIs for the frontend and parser.
2.  **`cas-parser` (Python Parser)**: A FastAPI-based service that uses `casparser` to extract investment data from PDF CAS statements and feeds it into the backend.
3.  **`cas-scraper` (Python Scraper)**: A utility script that fetches market indices fundamentals (PE, PB, etc.) from the NSE to provide market context and valuation metrics.
4.  **`portfolio-dashboard` (React Frontend)**: A modern, high-performance UI built with React and Framer Motion for a futuristic data visualization experience.
5.  **`PostgreSQL`**: The primary data store for investment records, market indices, and valuation history.

## Component Overview

| Component | Technology | Role |
| :--- | :--- | :--- |
| **Backend** | Java, Spring Boot, JPA | Business Logic, API, Database Management |
| **Parser** | Python, FastAPI, casparser | PDF Processing, Data Normalization |
| **Scraper** | Python, nsepython, pandas | Market Data Acquisition |
| **Frontend** | React, TypeScript, Tailwind | Data Visualization, UI |
| **Database** | PostgreSQL | Persistent Storage |

## Key Features

- **Automated CAS Import**: Seamlessly parse and inject mutual fund data from standard PDF statements.
- **Market Valuation**: Compare portfolio performance and valuations against benchmark indices (NIFTY 50, NIFTY 500, etc.).
- **Conviction Metrics**: Advanced logic to assess fund valuations based on market climate and benchmark fundamentals.
- **Futuristic UI**: A high-contrast, data-dense dashboard for an "architectural" view of your investments.

## Getting Started

### Prerequisites

- Docker and Docker Compose
- Maven (for local backend development)
- Python 3.10+ (for local parser/scraper development)
- Node.js & npm (for local frontend development)

### Deployment

To spin up the entire stack using Docker:

```bash
docker-compose up -d --build
```

The services will be available at:
- Frontend: `http://localhost`
- Backend: `http://localhost:8080`
- Parser: `http://localhost:8000`

---
For detailed design documentation of each component, refer to the `DESIGN.md` files in their respective directories.
