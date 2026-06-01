import io
import os
import re
import json
import logging
import httpx
from datetime import date, datetime
from decimal import Decimal
from typing import List, Dict, Any
from fastapi import FastAPI, UploadFile, Form, HTTPException, BackgroundTasks
from pydantic import BaseModel
import casparser
import numpy as np
import pandas as pd
import yfinance as yf
from rebalance_engine import RebalanceRequest, TacticalSignal, compute_signals
from scoring_engine import ScoringRequest, ScoringResponse, compute_conviction_score
from bootstrap_mc import monte_carlo_projection
from broker_parser import parse_indmoney_csv, parse_cdsl_statement
from stock_lot_engine import rebuild_lots_for_stock
import asyncpg


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)



app = FastAPI(title="Mutual Fund CAS Parser & Quant Service")
JAVA_MARKET_URL = os.getenv("JAVA_MARKET_URL", "http://java-backend:8080/api/market/index-history")
API_KEY = os.getenv("PORTFOLIO_API_KEY", "dev-secret-key")

DB_HOST = os.getenv("DB_HOST", "postgres")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "cas_db")
DB_USER = os.getenv("DB_USER", "user")
DB_PASS = os.getenv("DB_PASSWORD", "password")

db_pool = None

@app.on_event("startup")
async def startup_event():
    global db_pool
    db_pool = await asyncpg.create_pool(
        host=DB_HOST,
        port=DB_PORT,
        database=DB_NAME,
        user=DB_USER,
        password=DB_PASS
    )
    logger.info("Database pool initialized")

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

class MonteCarloRequest(BaseModel):
    daily_returns: List[float]
    current_value: float
    monthly_sip: float
    horizon_months: int
    n_simulations: int = 1000

class MonteCarloResponse(BaseModel):
    p10: float
    p50: float
    p90: float

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

class ReasoningRequest(BaseModel):
    fund_name: str
    current_weight: float
    target_weight: float
    conviction_score: float
    market_regime: str

class ChatRequest(BaseModel):
    query: str
    portfolio_summary: str
    history: List[Dict[str, str]] = []

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

@app.post("/api/v1/quant/monte-carlo", response_model=MonteCarloResponse)
async def run_monte_carlo(req: MonteCarloRequest):
    try:
        res = monte_carlo_projection(
            req.daily_returns,
            req.current_value,
            req.monthly_sip,
            req.horizon_months,
            req.n_simulations
        )
        return res
    except Exception as e:
        logger.error(f"Monte Carlo failed: {e}")
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

from quant_batch import run_batch_job

@app.post("/api/v1/quant/trigger-batch")
async def trigger_batch_job_endpoint(background_tasks: BackgroundTasks):
    logger.info("Received request to trigger Quant Batch Job")
    background_tasks.add_task(run_batch_job)
    return {"status": "accepted", "message": "Python Quant Batch Job triggered in background"}

@app.post("/api/scraper/sync-market")
async def sync_market_data():
    """Scrapes historical benchmark data from Yahoo Finance and pushes to Java Backend."""
    benchmarks = {
        "NIFTY 50": "^NSEI",
        "NIFTY MIDCAP 150": "^NSMIDCP150", 
        "NIFTY SMALLCAP 250": "^NSESCP250",
        "NIFTY 500": "^NSE500",
        "NIFTY 50 VALUE 20": "^NS50V20",
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
        logger.info(f"Parsed CAS for PAN: {payload['pan']}, Email: {payload['email']}")

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

        JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080/api")
        async with httpx.AsyncClient() as client:
            try:
                response = await client.post(
                    f"{JAVA_BACKEND_URL}/cas/ingest",
                    content=json_payload,
                    headers={
                        "Content-Type": "application/json",
                        "X-API-KEY": API_KEY
                    },
                    timeout=30.0
                )
                if response.status_code == 200:
                    return {"status": "accepted", "message": "CAS data is being processed asynchronously"}
                else:
                    raise HTTPException(status_code=500, detail=f"Backend rejected CAS data: {response.text}")
            except Exception as e:
                logger.error(f"Failed to push to Java backend: {e}")
                raise HTTPException(status_code=500, detail=f"Failed to push to Java backend: {str(e)}")

    except Exception as e:
        logger.error(f"CAS Parsing failed: {str(e)}")
        raise HTTPException(status_code=400, detail=str(e))

@app.post("/preview")
async def preview_cas(
    file: UploadFile,
    password: str = Form(...)
):
    try:
        content = await file.read()
        data = casparser.read_cas_pdf(io.BytesIO(content), password)
        
        if hasattr(data, "model_dump"):
            data = data.model_dump()
        elif hasattr(data, "dict"):
            data = data.dict()
            
        # Simplified summary for preview
        summary = {
            "investor": data.get("statement_period", {}).get("name"),
            "email": data.get("statement_period", {}).get("email"),
            "pan": data.get("statement_period", {}).get("pan"),
            "schemes_count": 0,
            "amcs": []
        }
        
        amcs_seen = set()
        for folio in data.get("folios", []):
            amcs_seen.add(folio.get("amc"))
            summary["schemes_count"] += len(folio.get("schemes", []))
            
        summary["amcs"] = sorted(list(amcs_seen))
        return summary
    except Exception as e:
        logger.error(f"CAS Preview failed: {str(e)}")
        raise HTTPException(status_code=400, detail=str(e))


import ai_reasoning

@app.post("/api/v1/ai/reason")
async def get_ai_reasoning(req: ReasoningRequest):
    reasoning = await ai_reasoning.generate_portfolio_reasoning(
        req.fund_name,
        req.current_weight,
        req.target_weight,
        req.conviction_score,
        req.market_regime
    )
    return {"reasoning": reasoning}

@app.post("/api/v1/ai/chat")
async def chat_with_portfolio(req: ChatRequest):
    response = await ai_reasoning.generate_portfolio_chat_response(
        req.query,
        req.portfolio_summary,
        req.history
    )
    return {"response": response}

@app.get("/api/v1/ai/health")
async def ai_health():
    return await ai_reasoning.check_ai_health()

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from quant_batch import run_batch_job

# --- 5. SCHEDULER ---
scheduler = AsyncIOScheduler(timezone="Asia/Kolkata")
scheduler.add_job(
    run_batch_job,
    trigger=CronTrigger(hour=19, minute=30, day_of_week='mon-fri'),
    id='nightly_quant_job',
    name='Nightly Quantitative Metrics Batch Job',
    replace_existing=True
)
scheduler.start()

@app.on_event("shutdown")
def shutdown_event():
    scheduler.shutdown()

@app.get("/health")
async def health_check():
    return {"status": "healthy", "scheduler_running": scheduler.running}

# --- 6. STOCK ROUTES ---

@app.post("/api/upload")
async def upload_stocks_file(
    file: UploadFile,
    pan: str = Form(...),
    source: str = Form("INDMONEY_CSV")
):
    try:
        content = await file.read()
        if source == "INDMONEY_CSV":
            records = parse_indmoney_csv(content)
        elif source == "CDSL":
            records = parse_cdsl_statement(content)
        else:
            raise HTTPException(status_code=400, detail=f"Unsupported source: {source}")

        # Format dates as ISO string for JSON serialization
        for r in records:
            if isinstance(r.get("transaction_date"), (date, datetime)):
                r["transaction_date"] = r["transaction_date"].isoformat()

        # Post to Java backend
        JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080/api")
        payload = {
            "pan": pan,
            "transactions": records
        }

        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{JAVA_BACKEND_URL}/v1/ingest/normalized-transactions",
                json=payload,
                headers={"X-API-KEY": API_KEY},
                timeout=60.0
            )
            if response.status_code == 200:
                return response.json()
            else:
                logger.error(f"Java backend returned error: {response.status_code} - {response.text}")
                raise HTTPException(status_code=500, detail=f"Java backend ingestion failed: {response.text}")

    except Exception as e:
        logger.error(f"Upload and ingestion failed: {e}")
        raise HTTPException(status_code=400, detail=str(e))

@app.post("/api/v1/stocks/rebuild-lots/{stock_id}")
async def rebuild_stock_lots(stock_id: int):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database pool not initialized")
    
    async with db_pool.acquire() as conn:
        try:
            await rebuild_lots_for_stock(conn, stock_id)
            return {"status": "success", "stock_id": stock_id}
        except Exception as e:
            logger.error(f"Failed to rebuild lots for stock {stock_id}: {e}")
            raise HTTPException(status_code=500, detail=str(e))
