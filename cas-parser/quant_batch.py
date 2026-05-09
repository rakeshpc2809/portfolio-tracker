import os
import logging
import datetime
import asyncio
import asyncpg
from typing import Dict, List, Any
from decimal import Decimal
import math

# Import existing logic
from scoring_engine import ScoringRequest, compute_conviction_score
from main import calculate_hurst_vectorized, calculate_ou_params_vectorized, calculate_hmm_regimes
from attribution_engine import run_attribution_analysis

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "portfolio_os")
DB_USER = os.getenv("DB_USER", "postgres")
DB_PASS = os.getenv("DB_PASSWORD", "postgres")

async def get_db_pool():
    return await asyncpg.create_pool(
        host=DB_HOST,
        port=DB_PORT,
        database=DB_NAME,
        user=DB_USER,
        password=DB_PASS
    )

async def fetch_all_navs(pool, days=253) -> Dict[str, Dict[str, List[float]]]:
    """Fetch last `days` of NAVs for all active funds asynchronously."""
    logger.info(f"Fetching last {days} days of NAVs from database...")
    query = """
        SELECT amfi_code, nav, nav_date
        FROM fund_history
        WHERE nav_date >= CURRENT_DATE - INTERVAL '400 days'
        ORDER BY amfi_code, nav_date DESC
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(query)
        
    data = {}
    for r in rows:
        amfi = str(r["amfi_code"]).lstrip('0')
        nav = float(r["nav"])
        if amfi not in data:
            data[amfi] = []
        data[amfi].append(nav)
        
    result = {}
    for amfi, navs in data.items():
        if len(navs) < days:
            continue
        navs_slice = navs[:days]
        navs_slice.reverse()
        
        returns = []
        for i in range(len(navs_slice) - 1):
            prev = navs_slice[i]
            curr = navs_slice[i+1]
            returns.append(math.log(curr / prev) if prev > 0 else 0.0)
            
        result[amfi] = {
            "navs": navs_slice,
            "returns": returns
        }
    return result

async def process_single_fund_metrics(conn, amfi: str, series: Dict[str, List[float]]):
    """Worker to compute metrics for a single fund and update DB."""
    try:
        # These are CPU intensive, but they use numpy which releases GIL sometimes.
        # In a true async app, these might still block, but we can offload to ThreadPool if needed.
        # For now, we'll run them directly as they are relatively fast per fund.
        hurst = calculate_hurst_vectorized(series["returns"])
        ou = calculate_ou_params_vectorized(series["navs"])
        states, bull, bear, trans = calculate_hmm_regimes(series["returns"])
        
        h_regime = "MEAN_REVERTING" if hurst < 0.47 else ("TRENDING" if hurst > 0.53 else "RANDOM_WALK")
        state_map = {0: "CALM_BULL", 1: "STRESSED_NEUTRAL", 2: "VOLATILE_BEAR"}
        hmm_state_str = state_map.get(states[-1], "UNKNOWN")
        
        update_query = """
            UPDATE fund_conviction_metrics
            SET hurst_exponent = $1,
                hurst_regime = $2,
                ou_half_life = $3,
                ou_valid = $4,
                hmm_state = $5,
                hmm_bull_prob = $6,
                hmm_bear_prob = $7,
                hmm_transition_bear = $8,
                last_python_update = CURRENT_TIMESTAMP
            WHERE LTRIM(amfi_code, '0') = $9
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE LTRIM(amfi_code, '0') = $10)
        """
        await conn.execute(update_query, 
            hurst, h_regime, ou["half_life"], ou["valid"],
            hmm_state_str, bull, bear, trans,
            amfi, amfi
        )
    except Exception as e:
        logger.error(f"Error computing advanced metrics for {amfi}: {e}")

async def run_batch_job():
    logger.info("🚀 Starting Async Quant Batch Job...")
    pool = await get_db_pool()
    
    try:
        nav_data = await fetch_all_navs(pool)
        logger.info(f"Fetched adequate history for {len(nav_data)} funds.")
        
        # 1. Update Hurst, OU, HMM (Parallel execution)
        logger.info("🧮 Computing HMM/Hurst/OU in parallel...")
        async with pool.acquire() as conn:
            tasks = [process_single_fund_metrics(conn, amfi, series) for amfi, series in nav_data.items()]
            await asyncio.gather(*tasks)
        
        logger.info("✅ Finished updating advanced metrics.")
        
        # 2. Run Fama-French Attribution Analysis
        await run_attribution_analysis(pool)
        
        # 3. Re-compute Conviction Scores
        async with pool.acquire() as conn:
            metrics_rows = await conn.fetch("""
                SELECT m.*, fm.expense_ratio, fm.aum_cr
                FROM fund_conviction_metrics m
                LEFT JOIN (
                    SELECT DISTINCT ON (scheme_code) scheme_code, expense_ratio, aum_cr
                    FROM fund_metrics
                    ORDER BY scheme_code, fetch_date DESC
                ) fm ON LTRIM(fm.scheme_code, '0') = LTRIM(m.amfi_code, '0')
                WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """)
            
            max_cagr = 15.0
            
            for m in metrics_rows:
                amfi = str(m["amfi_code"]).lstrip('0')
                
                req = ScoringRequest(
                    amfi_code=amfi,
                    personal_cagr=12.0,
                    max_cagr_found=max_cagr,
                    tax_pct_of_value=0.0,
                    category="EQUITY",
                    phil_status="ACTIVE",
                    sortino_ratio=float(m["sortino_ratio"] or 0.0),
                    rolling_z_score_252=float(m["rolling_z_score_252"] or 0.0),
                    max_drawdown=float(m["max_drawdown"] or 0.0),
                    ou_valid=bool(m["ou_valid"]),
                    ou_half_life=float(m["ou_half_life"] or 0.0),
                    hmm_bear_prob=float(m["hmm_bear_prob"] or 0.0),
                    expense_ratio=float(m["expense_ratio"] or 0.0),
                    aum_cr=float(m["aum_cr"] or 0.0),
                    nav_percentile_1yr=float(m["nav_percentile_1yr"] or 0.0)
                )
                
                try:
                    res = compute_conviction_score(req)
                    await conn.execute("""
                        UPDATE fund_conviction_metrics
                        SET yield_score = $1, risk_score = $2, value_score = $3,
                            pain_score = $4, regime_score = $5, friction_score = $6,
                            expense_score = $7, conviction_score = $8
                        WHERE amfi_code = $9 AND calculation_date = $10
                    """, 
                        res.yield_score, res.risk_score, res.value_score,
                        res.pain_recovery_score, res.regime_score, res.friction_score,
                        res.expense_score, int(round(res.final_conviction_score)),
                        m["amfi_code"], m["calculation_date"]
                    )
                except Exception as e:
                    logger.error(f"Error scoring {amfi}: {e}")
                    
        logger.info("✅ Finished updating Conviction Scores.")
        
    except Exception as e:
        logger.error(f"Async Batch job failed: {e}")
    finally:
        await pool.close()

if __name__ == "__main__":
    asyncio.run(run_batch_job())
