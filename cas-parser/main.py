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

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Mutual Fund CAS Parser Service")

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080/api/cas/inject")
API_KEY = os.getenv("PORTFOLIO_API_KEY", "dev-secret-key")

try:
    from hmmlearn import hmm
    HMM_AVAILABLE = True
except ImportError:
    HMM_AVAILABLE = False
    logger.warning("HMM service unavailable — install hmmlearn to enable regime detection.")

# --- 1. JSON ENCODER FOR DECIMAL & DATE ---
class AlphaNumericEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, (Decimal, date, datetime)):
            return str(obj)
        return super().default(obj)

# --- 2. MODELS ---
class HmmFitRequest(BaseModel):
    amfi_code: str
    returns: List[float]

class HmmFitResponse(BaseModel):
    amfi_code: str
    current_state: str          # CALM_BULL | STRESSED_NEUTRAL | VOLATILE_BEAR
    calm_bull_prob: float
    stressed_neutral_prob: float
    volatile_bear_prob: float
    transition_to_bear_prob: float

# --- 3. GLOBAL HELPERS ---
def format_date(date_str):
    if not date_str: return None
    for fmt in ("%d-%b-%Y", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(str(date_str), fmt).strftime("%Y-%m-%d")
        except (ValueError, TypeError):
            continue
    return date_str

def format_number(num):
    if num is None: return "0.00"
    # Ensure it's a string, then strip everything except digits, dots, and minus signs
    clean_num = re.sub(r'[^\d.-]', '', str(num))
    return clean_num if clean_num else "0.00"

def find_key_recursive(data, target_keys):
    """Deep search for PAN keys regardless of nesting level."""
    if isinstance(data, dict):
        for key, value in data.items():
            if key.lower() in target_keys and value:
                # If the value is a list (like ['ABCDE1234F']), take the first item
                return value[0] if isinstance(value, list) else value
            result = find_key_recursive(value, target_keys)
            if result: return result
    elif isinstance(data, list):
        for item in data:
            result = find_key_recursive(item, target_keys)
            if result: return result
    return None

def clean_data_for_java(data: dict):
    # 1. Name & Email (Mapping from your verified investor_info block)
    inv_info = data.get("investor_info", {})
    data["name"] = inv_info.get("name")
    data["email"] = inv_info.get("email")

    # 2. THE PAN FIX (Recursive Deep Search)
    # This looks for 'pan', 'pan_summary', or 'pan_no' anywhere in the JSON
    data["pan"] = find_key_recursive(data, ["pan", "pan_summary", "pan_no"])

    # 3. DEBUG LOG - Check your Docker logs for this line!
    logger.info(f"---> DISCOVERED PAN: {data['pan']}")

    # 4. NESTED MAPPINGS (Folio/Scheme/Tx)
    for folio in data.get("folios", []):
        folio["folio_number"] = folio.get("folio")
        for scheme in folio.get("schemes", []):
            scheme["name"] = scheme.get("scheme")
            scheme["amfiCode"] = scheme.get("amfi")
            for tx in scheme.get("transactions", []):
                tx["date"] = format_date(tx.get("date"))
                tx["amount"] = format_number(tx.get("amount"))
                tx["units"] = format_number(tx.get("units"))
                
                # Tagging logic (already verified)
                desc = str(tx.get("description", "")).upper()
                u_val = float(tx["units"]) if tx["units"] else 0.0
                if "STAMP DUTY" in desc: tx["transaction_type"] = "STAMP_DUTY"
                elif u_val > 0: tx["transaction_type"] = "BUY"
                elif u_val < 0: tx["transaction_type"] = "SELL"
                else: tx["transaction_type"] = "OTHER"
    
    return data

# --- 4. BACKEND COMMUNICATION ---
async def push_to_java_backend(parsed_data: dict):
    async with httpx.AsyncClient() as client:
        # Serialize using our custom encoder to fix the Decimal issue
        json_body = json.dumps(parsed_data, cls=AlphaNumericEncoder)
        
        response = await client.post(
            JAVA_BACKEND_URL,
            content=json_body,
            headers={
                "Content-Type": "application/json",
                "X-API-KEY": API_KEY
            },
            timeout=60.0 # Increased timeout for large PDFs
        )
        
        if response.status_code != 200:
            raise Exception(f"Java Backend error ({response.status_code}): {response.text}")
        
        logger.info("✅ Data successfully injected into Java Backend")

# --- 5. ROUTES ---
@app.post("/hmm/fit", response_model=HmmFitResponse)
async def fit_hmm(req: HmmFitRequest):
    if not HMM_AVAILABLE:
        raise HTTPException(status_code=503, detail="HMM service unavailable — install hmmlearn")
    
    try:
        X = np.array(req.returns).reshape(-1, 1)
        # Fit 3-state HMM
        model = hmm.GaussianHMM(n_components=3, covariance_type="full", n_iter=200, random_state=42)
        model.fit(X)
        
        # Current state (last observation)
        current_state_idx = model.predict(X)[-1]
        
        # State probabilities for the last observation
        probs = model.predict_proba(X)[-1]
        
        # Label states by sorting means (Ascending: Bear < Neutral < Bull)
        means = model.means_.flatten()
        sorted_indices = np.argsort(means)
        
        idx_to_label = {
            sorted_indices[0]: "VOLATILE_BEAR",
            sorted_indices[1]: "STRESSED_NEUTRAL",
            sorted_indices[2]: "CALM_BULL"
        }
        
        # Transition matrix
        transmat = model.transmat_
        bear_idx = sorted_indices[0]
        prob_to_bear = transmat[current_state_idx][bear_idx]
        
        return HmmFitResponse(
            amfi_code=req.amfi_code,
            current_state=idx_to_label[current_state_idx],
            calm_bull_prob=float(probs[sorted_indices[2]]),
            stressed_neutral_prob=float(probs[sorted_indices[1]]),
            volatile_bear_prob=float(probs[sorted_indices[0]]),
            transition_to_bear_prob=float(prob_to_bear)
        )
    except Exception as e:
        logger.error(f"HMM fit failed for {req.amfi_code}: {e}")
        return HmmFitResponse(
            amfi_code=req.amfi_code,
            current_state="STRESSED_NEUTRAL",
            calm_bull_prob=0.33,
            stressed_neutral_prob=0.33,
            volatile_bear_prob=0.33,
            transition_to_bear_prob=0.33
        )

@app.post("/api/parse")
async def parse_cas(file: UploadFile, password: str = Form(...)):
    if not file.filename.lower().endswith('.pdf'):
        raise HTTPException(status_code=400, detail="Only PDF files are supported.")
    
    try:
        content = await file.read()
        pdf_file = io.BytesIO(content)
        
        # 1. Parse PDF
        data_obj = casparser.read_cas_pdf(pdf_file, password)
        
        # 2. Convert to dict (handles different casparser versions)
        if hasattr(data_obj, "dict"):
            data = data_obj.dict()
        else:
            # Fallback for older casparser versions
            data = json.loads(data_obj.toJSON())
        
        # 3. Clean and Push
        cleaned_data = clean_data_for_java(data)
        await push_to_java_backend(cleaned_data)
        
        return {
            "status": "success", 
            "investor": cleaned_data.get("name"),
            "pan": cleaned_data.get("pan")
        }
    except Exception as e:
        logger.exception("CAS Parsing or Injection failed")
        raise HTTPException(status_code=500, detail=f"Service Error: {str(e)}")

@app.get("/health")
async def health_check():
    return {"status": "healthy", "target": JAVA_BACKEND_URL}

@app.on_event("startup")
async def startup():
    app.state.start_time = datetime.now()
