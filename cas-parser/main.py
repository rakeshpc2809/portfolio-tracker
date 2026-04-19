import io
import os
import re
import json
import logging
import httpx
from datetime import date, datetime
from decimal import Decimal
from typing import List, Dict, Any
from fastapi import FastAPI, UploadFile, Form, HTTPException
from pydantic import BaseModel
import casparser
import numpy as np
import pandas as pd
import yfinance as yf
from rebalance_engine import RebalanceRequest, TacticalSignal, compute_signals
from scoring_engine import ScoringRequest, ScoringResponse, compute_conviction_score
from confluent_kafka import Producer
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.zipkin.json import ZipkinExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
KAFKA_TOPIC = "cas.parsed.events"

try:
    producer_config = {
        'bootstrap.servers': KAFKA_BOOTSTRAP_SERVERS,
        'client.id': 'cas-parser-producer'
    }
    producer = Producer(producer_config)
    logger.info(f"✅ Kafka Producer initialized for {KAFKA_BOOTSTRAP_SERVERS}")
except Exception as e:
    logger.error(f"❌ Failed to initialize Kafka Producer: {e}")
    producer = None

# OTel Configuration
ZIPKIN_ENDPOINT = os.getenv("ZIPKIN_URL", "http://localhost:9411/api/v2/spans")
trace.set_tracer_provider(TracerProvider())
zipkin_exporter = ZipkinExporter(endpoint=ZIPKIN_ENDPOINT)
span_processor = BatchSpanProcessor(zipkin_exporter)
trace.get_tracer_provider().add_span_processor(span_processor)
tracer = trace.get_tracer("cas-parser")

app = FastAPI(title="Mutual Fund CAS Parser & Quant Service")
FastAPIInstrumentor.instrument_app(app)

JAVA_MARKET_URL = os.getenv("JAVA_MARKET_URL", "http://java-backend:8080/api/market/index-history")
API_KEY = os.getenv("PORTFOLIO_API_KEY", "dev-secret-key")

try:
    from hmmlearn import hmm
    HMM_AVAILABLE = True
except ImportError:
    HMM_AVAILABLE = False
    logger.warning("hmmlearn not installed. HMM features will be disabled.")

# --- 1. DATA MODELS ---
class HmmFitRequest(BaseModel):
    returns: List[float]
    n_states: int = 3

class HmmFitResponse(BaseModel):
    states: List[int]
    bull_prob: float
    bear_prob: float
    transition_to_bear: float

class QuantAnalyzeRequest(BaseModel):
    amfi_code: str
    navs: List[float]
    returns: List[float]

class QuantAnalyzeResponse(BaseModel):
    amfi_code: str
    hurst: float
    ou_half_life: float
    ou_valid: bool
    hmm_state: int
    bull_prob: float
    bear_prob: float
    transition_to_bear: float

class BatchAnalyzeRequest(BaseModel):
    funds: List[QuantAnalyzeRequest]

class BatchAnalyzeResponse(BaseModel):
    results: List[QuantAnalyzeResponse]

# --- 2. QUANT LOGIC ---

def calculate_hmm_regimes(returns_list: List[float], n_states: int = 3):
    if not HMM_AVAILABLE:
        logger.warning("HMM calculation skipped: hmmlearn not installed")
        return [0] * len(returns_list), 0.33, 0.33, 0.33
    
    if len(returns_list) < 50:
        logger.warning(f"HMM calculation skipped: insufficient data points ({len(returns_list)})")
        return [0] * len(returns_list), 0.33, 0.33, 0.33
    
    try:
        data = np.array(returns_list).reshape(-1, 1)
        model = hmm.GaussianHMM(n_components=n_states, covariance_type="diag", n_iter=1000, random_state=42)
        model.fit(data)

        if not model.monitor_.converged:
            logger.warning("HMM model did not converge")

        means = model.means_.flatten()
        # Sort indices by mean return descending: [Bull, Neutral, Bear]
        sorted_indices = np.argsort(means)[::-1]
        rank_map = {orig_idx: rank for rank, orig_idx in enumerate(sorted_indices)}

        # Current state (raw)
        curr_state_raw = model.predict(data)[-1]
        curr_state_mapped = rank_map[curr_state_raw]

        # Mapped probabilities
        probs_raw = model.predict_proba(data)[-1]
        bull_p = float(probs_raw[sorted_indices[0]])
        neutral_p = float(probs_raw[sorted_indices[1]])
        bear_p = float(probs_raw[sorted_indices[2]])

        # Transition to mapped Bear (state 2)
        trans_mat_raw = model.transmat_
        # Probability of moving from current raw state to raw state that represents Bear
        to_bear_p = float(trans_mat_raw[curr_state_raw][sorted_indices[2]])
        
        # Map all states
        all_states_raw = model.predict(data)
        all_states_mapped = [rank_map[s] for s in all_states_raw]

        return all_states_mapped, bull_p, bear_p, to_bear_p
    except Exception as e:
        logger.error(f"HMM Fitting failed: {str(e)}")
        return [0] * len(returns_list), 0.33, 0.33, 0.33

def calculate_hurst_vectorized(ts):
    """Vectorized Hurst Exponent via Rescaled Range."""
    ts = np.array(ts)
    if len(ts) < 50: return 0.5
    
    lags = range(2, 20)
    tau = [np.sqrt(np.std(np.subtract(ts[lag:], ts[:-lag]))) for lag in lags]
    poly = np.polyfit(np.log(lags), np.log(tau), 1)
    return float(poly[0] * 2.0)

def calculate_ou_params_vectorized(navs):
    """Vectorized OU parameter estimation."""
    navs = np.array(navs)
    if len(navs) < 30: return {"half_life": 0.0, "valid": False}
    
    y = np.log(navs)
    x = y[:-1]
    dy = np.diff(y)
    
    # Simple linear regression for OU process parameters: dy = (a + b*x) * dt
    poly = np.polyfit(x, dy, 1)
    b, a = poly
    
    if b >= 0: # Process is non-stationary / diverging
        return {"half_life": 0.0, "valid": False}
    
    theta = -b
    mu = -a / b
    half_life = np.log(2) / theta
    
    return {
        "theta": float(theta),
        "mu": float(mu),
        "half_life": float(half_life),
        "valid": True
    }

# --- 3. HELPERS ---
def decimal_default(obj):
    if isinstance(obj, Decimal): return float(obj)
    if isinstance(obj, (date, datetime)): return obj.isoformat()
    raise TypeError

# --- 4. ROUTES ---

@app.post("/api/v1/quant/rebalance", response_model=List[TacticalSignal])
async def rebalance_portfolio(req: RebalanceRequest):
    try:
        signals = compute_signals(req)
        return signals
    except Exception as e:
        logger.error(f"Rebalance computation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/v1/quant/score", response_model=ScoringResponse)
async def score_fund(req: ScoringRequest):
    try:
        return compute_conviction_score(req)
    except Exception as e:
        logger.error(f"Scoring computation failed for {req.amfi_code}: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/v1/quant/analyze", response_model=QuantAnalyzeResponse)
async def analyze_quant(req: QuantAnalyzeRequest):
    try:
        hurst = calculate_hurst_vectorized(req.returns)
        ou = calculate_ou_params_vectorized(req.navs)
        states, bull, bear, trans = calculate_hmm_regimes(req.returns)
        
        return {
            "amfi_code": req.amfi_code,
            "hurst": hurst,
            "ou_half_life": ou["half_life"],
            "ou_valid": ou["valid"],
            "hmm_state": states[-1],
            "bull_prob": bull,
            "bear_prob": bear,
            "transition_to_bear": trans
        }
    except Exception as e:
        logger.error(f"Quant analysis failed for {req.amfi_code}: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/v1/quant/analyze-batch", response_model=BatchAnalyzeResponse)
async def analyze_batch_quant(req: BatchAnalyzeRequest):
    results = []
    for fund in req.funds:
        try:
            hurst = calculate_hurst_vectorized(fund.returns)
            ou = calculate_ou_params_vectorized(fund.navs)
            states, bull, bear, trans = calculate_hmm_regimes(fund.returns)
            
            results.append({
                "amfi_code": fund.amfi_code,
                "hurst": hurst,
                "ou_half_life": ou["half_life"],
                "ou_valid": ou["valid"],
                "hmm_state": states[-1],
                "bull_prob": bull,
                "bear_prob": bear,
                "transition_to_bear": trans
            })
        except Exception as e:
            logger.error(f"Quant analysis failed for {fund.amfi_code} in batch: {e}")
            # We continue with other funds if one fails
    
    return {"results": results}

@app.post("/api/scraper/sync-market")
async def sync_market_data():
    """Scrapes historical benchmark data from Yahoo Finance and pushes to Java Backend."""
    benchmarks = {
        "NIFTY 50": "^NSEI",
        "NIFTY MIDCAP 150": "^NSMIDCP150", 
        "NIFTY SMALLCAP 250": "^NSESCP250",
        "NIFTY 500": "^NSE500",
        "GOLD_PRICE_INDEX": "GC=F"
    }

    results = []
    async with httpx.AsyncClient() as client:
        for index_name, ticker_symbol in benchmarks.items():
            try:
                logger.info(f"Scraping data for {index_name} ({ticker_symbol})")
                ticker = yf.Ticker(ticker_symbol)
                # Fetch last 2 years to ensure enough data points
                hist = ticker.history(period="2y")
                
                if hist.empty:
                    logger.warning(f"No data returned for {ticker_symbol}")
                    continue

                for timestamp, row in hist.iterrows():
                    results.append({
                        "indexName": index_name,
                        "date": timestamp.strftime("%Y-%m-%d"),
                        "closingPrice": float(row['Close'])
                    })
            except Exception as e:
                logger.error(f"Failed to scrape {index_name}: {e}")

        if results:
            try:
                response = await client.post(
                    JAVA_MARKET_URL,
                    json=results,
                    headers={"X-API-KEY": API_KEY},
                    timeout=60.0
                )
                return {"status": "success", "synced": len(results), "java_response": response.text}
            except Exception as e:
                logger.error(f"Failed to push to Java: {e}")
                raise HTTPException(status_code=500, detail=f"Failed to push: {str(e)}")
    
    return {"status": "no_data"}

@app.post("/hmm/fit", response_model=HmmFitResponse)
async def fit_hmm(req: HmmFitRequest):
    if not HMM_AVAILABLE:
        raise HTTPException(status_code=53, detail="HMM service unavailable")
    
    try:
        states, bull, bear, trans = calculate_hmm_regimes(req.returns, req.n_states)
        return {
            "states": states,
            "bull_prob": bull,
            "bear_prob": bear,
            "transition_to_bear": trans
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/parse")
async def parse_cas(
    file: UploadFile,
    password: str = Form(...)
):
    try:
        content = await file.read()
        data = casparser.read_cas_pdf(io.BytesIO(content), password)
        
        # Convert to dict if it's a Pydantic model (casparser 0.8+)
        if hasattr(data, "model_dump"):
            data = data.model_dump()
        elif hasattr(data, "dict"):
            data = data.dict()
        
        # Structure the data for the Java backend
        payload = {
            "pan": data.get("statement_period", {}).get("pan"),
            "email": data.get("statement_period", {}).get("email"),
            "name": data.get("statement_period", {}).get("name"),
            "folios": []
        }

        for folio in data.get("folios", []):
            f_data = {
                "folio_number": folio.get("folio"),
                "amc": folio.get("amc"),
                "schemes": []
            }
            for scheme in folio.get("schemes", []):
                s_data = {
                    "name": scheme.get("scheme"),
                    "isin": scheme.get("isin"),
                    "amfi": scheme.get("amfi"),
                    "transactions": []
                }
                for tx in scheme.get("transactions", []):
                    s_data["transactions"].append({
                        "date": tx.get("date"),
                        "description": tx.get("description"),
                        "amount": tx.get("amount"),
                        "units": tx.get("units"),
                        "transaction_type": tx.get("type")
                    })
                f_data["schemes"].append(s_data)
            payload["folios"].append(f_data)
        
        json_payload = json.dumps(payload, default=decimal_default)

        # Asynchronously forward to Kafka
        if producer:
            try:
                producer.produce(KAFKA_TOPIC, value=json_payload, callback=lambda err, msg: logger.info(f"Kafka message delivered") if err is None else logger.error(f"Kafka delivery failed: {err}"))
                producer.flush()
                return {"status": "accepted", "message": "CAS data is being processed asynchronously"}
            except Exception as e:
                logger.error(f"Failed to push to Kafka: {e}")
                # Fallback to sync REST if producer fails? 
                # For now, let's just fail if Kafka is the primary
                raise HTTPException(status_code=500, detail=f"Failed to push to Kafka: {str(e)}")
        else:
            raise HTTPException(status_code=503, detail="Kafka producer not initialized")

    except Exception as e:
        logger.error(f"CAS Parsing failed: {str(e)}")
        raise HTTPException(status_code=400, detail=str(e))

@app.get("/health")
async def health_check():
    return {"status": "healthy"}
