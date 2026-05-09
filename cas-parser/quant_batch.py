import os
import logging
import datetime
import psycopg2
import psycopg2.extras
from typing import Dict, List, Any
from decimal import Decimal

# Import existing logic without touching it
from scoring_engine import ScoringRequest, compute_conviction_score
from main import calculate_hurst_vectorized, calculate_ou_params_vectorized, calculate_hmm_regimes

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "portfolio_os")
DB_USER = os.getenv("DB_USER", "postgres")
DB_PASS = os.getenv("DB_PASSWORD", "postgres")

def get_db_connection():
    return psycopg2.connect(
        host=DB_HOST,
        port=DB_PORT,
        dbname=DB_NAME,
        user=DB_USER,
        password=DB_PASS
    )

def fetch_all_navs(conn, days=253) -> Dict[str, Dict[str, List[float]]]:
    """Fetch last `days` of NAVs for all active funds."""
    logger.info(f"Fetching last {days} days of NAVs from database...")
    query = f"""
        SELECT amfi_code, nav, nav_date
        FROM fund_history
        WHERE nav_date >= CURRENT_DATE - INTERVAL '400 days'
        ORDER BY amfi_code, nav_date DESC
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(query)
        rows = cur.fetchall()
        
    data = {}
    for r in rows:
        amfi = str(r["amfi_code"]).lstrip('0')
        nav = float(r["nav"])
        if amfi not in data:
            data[amfi] = []
        data[amfi].append(nav)
        
    # Process into returns
    result = {}
    for amfi, navs in data.items():
        if len(navs) < days:
            continue
        # take latest `days`
        navs_slice = navs[:days]
        # Reverse to chronological order for math
        navs_slice.reverse()
        
        import math
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

def run_batch_job():
    logger.info("🚀 Starting Quant Batch Job...")
    try:
        conn = get_db_connection()
    except Exception as e:
        logger.error(f"Failed to connect to DB: {e}")
        return
        
    try:
        nav_data = fetch_all_navs(conn)
        logger.info(f"Fetched adequate history for {len(nav_data)} funds.")
        
        # 1. Update Hurst, OU, HMM
        for amfi, series in nav_data.items():
            try:
                hurst = calculate_hurst_vectorized(series["returns"])
                ou = calculate_ou_params_vectorized(series["navs"])
                states, bull, bear, trans = calculate_hmm_regimes(series["returns"])
                
                h_regime = "MEAN_REVERTING" if hurst < 0.47 else ("TRENDING" if hurst > 0.53 else "RANDOM_WALK")
                state_map = {0: "CALM_BULL", 1: "STRESSED_NEUTRAL", 2: "VOLATILE_BEAR"}
                hmm_state_str = state_map.get(states[-1], "UNKNOWN")
                
                update_query = """
                    UPDATE fund_conviction_metrics
                    SET hurst_exponent = %s,
                        hurst_regime = %s,
                        ou_half_life = %s,
                        ou_valid = %s,
                        hmm_state = %s,
                        hmm_bull_prob = %s,
                        hmm_bear_prob = %s,
                        hmm_transition_bear = %s,
                        last_python_update = CURRENT_TIMESTAMP
                    WHERE LTRIM(amfi_code, '0') = %s
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE LTRIM(amfi_code, '0') = %s)
                """
                with conn.cursor() as cur:
                    cur.execute(update_query, (
                        hurst, h_regime, ou["half_life"], ou["valid"],
                        hmm_state_str, bull, bear, trans,
                        amfi, amfi
                    ))
            except Exception as e:
                logger.error(f"Error computing advanced metrics for {amfi}: {e}")
        
        conn.commit()
        logger.info("✅ Finished updating advanced metrics (Hurst, OU, HMM).")
        
        # 2. Re-compute Conviction Scores
        # For a full batch job, we would read the current metrics from fund_conviction_metrics
        # and re-run compute_conviction_score(), then update the DB.
        # This keeps the scoring engine completely decoupled from Java HTTP calls.
        
        with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
            cur.execute("""
                SELECT m.*, fm.expense_ratio, fm.aum_cr
                FROM fund_conviction_metrics m
                LEFT JOIN (
                    SELECT DISTINCT ON (scheme_code) scheme_code, expense_ratio, aum_cr
                    FROM fund_metrics
                    ORDER BY scheme_code, fetch_date DESC
                ) fm ON LTRIM(fm.scheme_code, '0') = LTRIM(m.amfi_code, '0')
                WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """)
            metrics = cur.fetchall()
            
            # For personal CAGR and max CAGR, we'd need tax lots.
            # For simplicity in this translated batch script, we will query max CAGR from DB or use 15.0
            max_cagr = 15.0
            
            for m in metrics:
                amfi = str(m["amfi_code"]).lstrip('0')
                
                req = ScoringRequest(
                    amfi_code=amfi,
                    personal_cagr=12.0, # Placeholder, realistically pulled from DB views
                    max_cagr_found=max_cagr,
                    tax_pct_of_value=0.0, # Placeholder
                    category="EQUITY", # Placeholder
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
                    cur.execute("""
                        UPDATE fund_conviction_metrics
                        SET yield_score = %s, risk_score = %s, value_score = %s,
                            pain_score = %s, regime_score = %s, friction_score = %s,
                            expense_score = %s, conviction_score = %s
                        WHERE amfi_code = %s AND calculation_date = %s
                    """, (
                        res.yield_score, res.risk_score, res.value_score,
                        res.pain_recovery_score, res.regime_score, res.friction_score,
                        res.expense_score, int(round(res.final_conviction_score)),
                        m["amfi_code"], m["calculation_date"]
                    ))
                    conn.commit()
                except Exception as e:
                    logger.error(f"Error scoring {m['amfi_code']}: {e}")
                    conn.rollback()
                    
        conn.commit()
        logger.info("✅ Finished updating Conviction Scores.")
        
    except Exception as e:
        logger.error(f"Batch job failed: {e}")
        conn.rollback()
    finally:
        conn.close()

if __name__ == "__main__":
    run_batch_job()
