import os
import logging
import numpy as np
import pandas as pd
import httpx
from typing import List
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from apscheduler.schedulers.asyncio import AsyncIOScheduler

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = FastAPI(title="Quant Engine Sidecar", version="1.0.0")

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080/api")
API_KEY = os.getenv("PORTFOLIO_API_KEY", "dev-secret-key")
AMFI_URL = "https://www.amfiindia.com/spages/NAVAll.txt"

scheduler = AsyncIOScheduler()

class NavListInput(BaseModel):
    navs: List[float]

def calculate_hurst_exponent(ts):
    ts = np.array(ts)
    if len(ts) < 50:
        return 0.5
    lags = range(2, 20)
    tau = [np.sqrt(np.std(np.subtract(ts[lag:], ts[:-lag]))) for lag in lags]
    poly = np.polyfit(np.log(lags), np.log(tau), 1)
    return float(poly[0] * 2.0)

@app.post("/api/v1/quant/metrics")
def get_quant_metrics(input_data: NavListInput):
    navs = input_data.navs
    if not navs:
        raise HTTPException(status_code=400, detail="NAV list cannot be empty")

    df = pd.Series(navs)
    
    # Calculate Rolling 252-day Z-Score
    if len(df) >= 252:
        rolling_mean = df.rolling(252).mean()
        rolling_std = df.rolling(252).std(ddof=1)
        current_nav = df.iloc[-1]
        avg_252 = rolling_mean.iloc[-1]
        std_252 = rolling_std.iloc[-1]
        z_score = (current_nav - avg_252) / std_252 if std_252 > 0 else 0.0
    else:
        current_nav = df.iloc[-1]
        avg_all = df.mean()
        std_all = df.std(ddof=1) if len(df) > 1 else 0.0
        z_score = (current_nav - avg_all) / std_all if std_all > 0 else 0.0

    # Calculate Hurst Exponent
    hurst_val = calculate_hurst_exponent(navs)
    regime = "TRENDING" if hurst_val > 0.5 else ("MEAN_REVERTING" if hurst_val < 0.5 else "RANDOM_WALK")

    return {
        "rolling_z_score_252": z_score,
        "hurst_exponent": hurst_val,
        "hurst_regime": regime
    }

async def scrape_amfi_navs():
    logger.info("Scraping AMFI NAVs...")
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(AMFI_URL, timeout=60.0, follow_redirects=True)
            if response.status_code != 200:
                logger.error(f"Failed to fetch AMFI NAVs: status {response.status_code}")
                return []
            
            from datetime import datetime
            lines = response.text.splitlines()
            records = []
            for line in lines:
                parts = line.split(";")
                if len(parts) == 6:
                    amfi_code = parts[0].strip()
                    if not amfi_code.isdigit():
                        continue
                    try:
                        nav_str = parts[4].strip()
                        date_str = parts[5].strip()
                        if not nav_str or not date_str:
                            continue
                        
                        nav = float(nav_str)
                        parsed_date = datetime.strptime(date_str, "%d-%b-%Y").strftime("%Y-%m-%d")
                        
                        records.append({
                            "amfiCode": amfi_code,
                            "navDate": parsed_date,
                            "nav": nav
                        })
                    except Exception:
                        continue
            
            logger.info(f"Successfully scraped {len(records)} AMFI NAV records.")
            return records
        except Exception as e:
            logger.error(f"Error scraping AMFI NAVs: {e}")
            return []

async def push_navs_to_java(records):
    if not records:
        return
    logger.info(f"Pushing {len(records)} NAVs to Java Core...")
    url = f"{JAVA_BACKEND_URL}/history/nav"
    async with httpx.AsyncClient() as client:
        try:
            batch_size = 1000
            for i in range(0, len(records), batch_size):
                batch = records[i:i+batch_size]
                response = await client.post(
                    url,
                    json=batch,
                    headers={"X-API-KEY": API_KEY},
                    timeout=60.0
                )
                if response.status_code != 200:
                    logger.error(f"Failed to push batch to Java: status {response.status_code}, response: {response.text}")
                else:
                    logger.info(f"Successfully pushed batch {i // batch_size + 1} of {len(records) // batch_size + 1} to Java.")
        except Exception as e:
            logger.error(f"Error pushing NAVs to Java: {e}")

@scheduler.scheduled_job('cron', hour=23, minute=45, timezone='Asia/Kolkata')
async def daily_amfi_sync():
    logger.info("Triggering scheduled daily AMFI NAV sync...")
    records = await scrape_amfi_navs()
    if records:
        await push_navs_to_java(records)

@app.post("/api/v1/quant/trigger-sync")
async def trigger_sync():
    records = await scrape_amfi_navs()
    if records:
        await push_navs_to_java(records)
        return {"status": "success", "message": f"Synced {len(records)} records"}
    return {"status": "error", "message": "No records scraped"}

@app.on_event("startup")
async def start_scheduler():
    scheduler.start()
    logger.info("APScheduler started.")

@app.on_event("shutdown")
async def shutdown_scheduler():
    scheduler.shutdown()
    logger.info("APScheduler shutdown.")
