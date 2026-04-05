from fastapi import FastAPI, BackgroundTasks
import subprocess
import os
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Portfolio Metrics Scraper Service")

def run_script(script_name: str):
    logger.info(f"🚀 Starting script: {script_name}")
    try:
        # Use the same python interpreter that's running the app
        result = subprocess.run(["python3", script_name], capture_output=True, text=True, check=True)
        logger.info(f"✅ Script {script_name} finished successfully")
        logger.debug(f"STDOUT: {result.stdout}")
    except subprocess.CalledProcessError as e:
        logger.error(f"❌ Script {script_name} failed with exit code {e.returncode}")
        logger.error(f"STDERR: {e.stderr}")
    except Exception as e:
        logger.error(f"❌ Unexpected error running {script_name}: {str(e)}")

@app.post("/api/scraper/sync-market")
async def sync_market(background_tasks: BackgroundTasks):
    background_tasks.add_task(run_script, "scraper.py")
    return {"status": "accepted", "message": "Market data sync started in background"}

@app.post("/api/scraper/sync-metrics")
async def sync_metrics(background_tasks: BackgroundTasks):
    background_tasks.add_task(run_script, "metrics_engine.py")
    return {"status": "accepted", "message": "MF metrics sync started in background"}

@app.get("/health")
async def health_check():
    return {"status": "healthy"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)
