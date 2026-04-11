# cas-parser (Python Sidecar)

A microservice designed to handle mathematical or data-science workloads that are cumbersome in Java.

## Core Responsibilities

1. **HMM Regime Detection**: Exposes a `/hmm/fit` endpoint. It receives a time-series of log returns from the Java backend, fits a Gaussian Hidden Markov Model (`hmmlearn`), and returns:
   - Current State (`CALM_BULL`, `STRESSED_NEUTRAL`, `VOLATILE_BEAR`).
   - Transition probabilities.
   This data is persisted by the Java backend and used to filter out false "buy the dip" signals during structural market crashes.

## Technology Stack
- Python 3.11+
- FastAPI
- Uvicorn
- scikit-learn
- hmmlearn
