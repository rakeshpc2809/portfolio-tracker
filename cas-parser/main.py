import io
import os
import re
import json
import logging
import httpx
from datetime import date, datetime
from decimal import Decimal
from typing import List
from fastapi import FastAPI, UploadFile, Form, HTTPException
from pydantic import BaseModel
import casparser
import numpy as np
import yfinance as yf

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Mutual Fund CAS Parser Service")

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080/api/cas/inject")
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

# --- 2. HMM LOGIC ---
def calculate_hmm_regimes(returns_list: List[float], n_states: int = 3):
    if not HMM_AVAILABLE:
        return [0] * len(returns_list), 0.33, 0.33, 0.33
    
    data = np.array(returns_list).reshape(-1, 1)
    model = hmm.GaussianHMM(n_components=n_states, covariance_type="diag", n_iter=1000)
    model.fit(data)
    states = model.predict(data)
    
    means = model.means_.flatten()
    bull_idx = np.argmax(means)
    bear_idx = np.argmin(means)
    
    curr_state = states[-1]
    probs = model.predict_proba(data)[-1]
    
    bull_p = float(probs[bull_idx])
    bear_p = float(probs[bear_idx])
    
    trans_mat = model.transmat_
    to_bear_p = float(trans_mat[curr_state][bear_idx])
    
    return states.tolist(), bull_p, bear_p, to_bear_p

# --- 3. HELPERS ---
def decimal_default(obj):
    if isinstance(obj, Decimal): return float(obj)
    if isinstance(obj, (date, datetime)): return obj.isoformat()
    raise TypeError

# --- 4. ROUTES ---
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
        raise HTTPException(status_code=503, detail="HMM service unavailable")
    
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

        # Forward to Java Backend
        async with httpx.AsyncClient() as client:
            response = await client.post(
                JAVA_BACKEND_URL,
                json=json.loads(json.dumps(payload, default=decimal_default)),
                headers={"X-API-KEY": API_KEY},
                timeout=60.0
            )
            
            if response.status_code != 200:
                raise Exception(f"Java Backend error: {response.text}")

            return response.json()

    except Exception as e:
        logger.error(f"CAS Parsing failed: {str(e)}")
        raise HTTPException(status_code=400, detail=str(e))

@app.get("/health")
async def health_check():
    return {"status": "healthy", "target": JAVA_BACKEND_URL}
