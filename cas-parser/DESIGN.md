# Design Document: cas-parser

The `cas-parser` is a Python-based microservice that handles the parsing and normalization of PDF mutual fund Consolidated Account Statements (CAS).

## Responsibilities

1.  **PDF Parsing**: Uses the `casparser` library to extract data from PDF statements.
2.  **Data Normalization**: Cleans and transforms the raw extracted data into a structured format expected by the Java backend.
3.  **Backend Integration**: Pushes normalized portfolio data to the `cas-injector` service for persistence.

## Core Logic

### 1. FastAPI Service
The service exposes an endpoint to upload PDF statements and initiates the parsing process.

### 2. Data Cleaning & Mapping (`clean_data_for_java`)
This is a critical function that:
-   **Extracts Investor Info**: Recursively searches for PAN, Name, and Email.
-   **Normalizes Transactions**: Maps transaction descriptions to standardized types (`BUY`, `SELL`, `STAMP_DUTY`).
-   **Formats Data**: Ensures dates and numbers are in a format compatible with the backend's JSON expectations.

### 3. Asynchronous Pushing
Uses `httpx.AsyncClient` to asynchronously push large transaction datasets to the backend, ensuring the parser remains responsive.

## Technology Stack

-   **Language**: Python 3.10+
-   **Web Framework**: FastAPI
-   **Parsing Library**: `casparser`
-   **HTTP Client**: `httpx`
-   **Logging**: Standard `logging` module for auditability.

## Key Design Considerations

-   **Robustness**: The deep recursive search for PAN numbers ensures data extraction works even with variations in statement formats.
-   **Type Safety**: A custom JSON encoder (`AlphaNumericEncoder`) is used to handle Decimals and Date objects during serialization.
