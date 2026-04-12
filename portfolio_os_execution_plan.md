# Portfolio OS: Architectural Execution Plan

This document is formatted as a set of deterministic, step-by-step instructions. It is designed to be fed into an AI CLI agent (like Gemini CLI, Aider, or Cursor) to systematically execute the architectural refactoring of the Portfolio OS codebase.

---

## Phase 1: High-Throughput CAS Ingestion & Persistence Refactor
**Objective:** Eliminate N+1 JPA query bottlenecks and memory exhaustion during CAS file processing by migrating to JDBC Batching and CQRS-style read models.

### Step 1.1: Dependency & Migration Setup
* **Target Files:** `pom.xml`, `src/main/resources/db/migration/`
* **Action:** 1. Ensure `spring-boot-starter-data-jdbc` or `spring-jdbc` is present in `pom.xml`.
    2. Create a new Flyway/Liquibase migration script (e.g., `V2__Optimize_Transaction_Views.sql`).
    3. Define a PostgreSQL Materialized View: `CREATE MATERIALIZED VIEW mv_portfolio_summary AS SELECT ...` flattening the joins across `Transaction`, `Scheme`, `Folio`, and `Investor`. Add a unique index for concurrent refreshes.

### Step 1.2: Implement JDBC Batching
* **Target Files:** `CasProcessingService.java`, `TransactionRepository.java`
* **Action:** 1. Deprecate the JPA `txnRepo.save(tx)` inside the processing loop.
    2. Inject `JdbcTemplate` into `CasProcessingService`.
    3. Implement `jdbcTemplate.batchUpdate()` to insert parsed CAS transactions in chunks of 500-1000.
    4. Ensure the transaction boundary (`@Transactional`) wraps the entire batch operation.

### Step 1.3: Trigger View Refreshes
* **Target Files:** `CasProcessingService.java`, `TransactionService.java`
* **Action:** 1. After a successful batch ingestion, execute a native SQL call: `REFRESH MATERIALIZED VIEW CONCURRENTLY mv_portfolio_summary;`.
    2. Refactor `TransactionService` pagination logic to query `mv_portfolio_summary` instead of using complex JPA Specifications.

---

## Phase 2: React Frontend Resilience & State Management
**Objective:** Replace fragile `useEffect` data fetching with robust, cached state management to handle dynamic quantitative data smoothly.

### Step 2.1: React Query Setup
* **Target Files:** `portfolio-dashboard/package.json`, `portfolio-dashboard/src/main.tsx`
* **Action:** 1. Install dependencies: `npm install @tanstack/react-query @tanstack/react-query-devtools`.
    2. Wrap the root React application (`<App />`) with `QueryClientProvider` and initialize a `new QueryClient()`.

### Step 2.2: Refactor Components
* **Target Files:** `PortfolioView.tsx`, `PerformanceView.tsx`, `LedgerView.tsx` (and related API client files).
* **Action:** 1. Strip out raw `useEffect` and `useState` combinations used for data fetching.
    2. Implement custom hooks (e.g., `usePortfolioSummary()`, `useFundPerformance()`) utilizing `useQuery`.
    3. Configure `staleTime` appropriately (e.g., 5 minutes) to prevent redundant backend calls during component remounts.

### Step 2.3: WebSocket Cache Invalidation
* **Target Files:** `portfolio-dashboard/src/hooks/useEngineWebsocket.ts` (or similar WebSocket hook).
* **Action:** 1. Listen for the nightly engine's "COMPLETED" STOMP message.
    2. Upon receipt, trigger `queryClient.invalidateQueries({ queryKey: ['portfolio'] })` to automatically re-fetch fresh data without user intervention.

---

## Phase 3: Quantitative Engine Vectorization
**Objective:** Prevent JVM Heap exhaustion by offloading heavy array mathematics (Hurst, Ornstein-Uhlenbeck) from Java loops to the Python Fast API sidecar.

### Step 3.1: Python Sidecar API Expansion
* **Target Files:** `cas-parser/main.py`, `cas-parser/requirements.txt`
* **Action:** 1. Ensure `numpy`, `pandas`, and `scipy` are in `requirements.txt`.
    2. Create a new FastAPI POST endpoint: `/api/v1/quant/analyze`.
    3. The endpoint should accept a JSON payload containing arrays of historical NAVs and Returns.
    4. Implement vectorized Python functions to calculate the Hurst Exponent, OU Mean-Reversion metrics, and HMM state probabilities. Return a structured JSON response.

### Step 3.2: Java Engine Delegation
* **Target Files:** `QuantitativeEngineService.java`, `PythonIntegrationClient.java` (or similar RestClient wrapper).
* **Action:** 1. Remove the local Java loops computing Hurst and OU.
    2. Aggregate the required 400-day historical data from the database using a custom projection to minimize memory footprint.
    3. Send the aggregated arrays to the Python sidecar via a Spring `RestClient` or `WebClient`.
    4. Parse the returning `FeatureAttribution` metrics and persist them to the database.

---

## Phase 4: Local AI Narrative Generation
**Objective:** Replace static string templates with dynamic, auditable financial reasoning using a local LLM via Spring AI.

### Step 4.1: Spring AI & Ollama Integration
* **Target Files:** `pom.xml`, `application.yml`
* **Action:** 1. Add the `spring-ai-ollama-spring-boot-starter` dependency.
    2. Configure `application.yml` to point to the local Ollama instance (e.g., `spring.ai.ollama.base-url=http://localhost:11434`, `spring.ai.ollama.chat.options.model=qwen2.5:8b`).

### Step 4.2: Structured Output Generation
* **Target Files:** `ReasoningMetadata.java`, `AiNarrativeService.java`
* **Action:** 1. Define `ReasoningMetadata` as a Java `Record` containing fields for `tacticalStance`, `riskWarning`, and `rebalancingAdvice`.
    2. Create `AiNarrativeService` using Spring AI's `ChatClient`.
    3. Design a rigorous system prompt: "You are a quantitative mutual fund advisor. Analyze the provided Hurst, OU, and HMM metrics. Enforce a long-term holding philosophy for core assets."
    4. Implement the `BeanOutputConverter<ReasoningMetadata>` to force the LLM to return strict JSON matching the record structure.
    5. Wire this service to run asynchronously at the end of the nightly quantitative engine run, updating the database with the generated narratives.
