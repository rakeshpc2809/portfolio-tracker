import logging
import pandas as pd
import numpy as np
from sklearn.linear_model import LinearRegression
from typing import Dict, List, Any

logger = logging.getLogger(__name__)

async def run_attribution_analysis(pool):
    """
    Performs Fama-French 3-Factor style attribution analysis for all funds.
    Factors:
    - Mkt: Nifty 50
    - SMB (Size): Nifty Smallcap 250 - Nifty 50
    - HML (Value): Nifty 50 Value 20 - Nifty 50
    """
    logger.info("📈 Starting Fama-French Attribution Analysis...")
    
    async with pool.acquire() as conn:
        # 1. Fetch Factor Data
        indices = ["NIFTY 50", "NIFTY SMALLCAP 250", "NIFTY 500", "NIFTY 50 VALUE 20"]
        index_data_rows = await conn.fetch("""
            SELECT index_name, date, closing_price
            FROM index_fundamentals
            WHERE index_name = ANY($1)
            AND date >= CURRENT_DATE - INTERVAL '400 days'
            ORDER BY date ASC
        """, indices)
        
        if not index_data_rows:
            logger.warning("⚠️ No index data found for attribution analysis.")
            return

        df_indices = pd.DataFrame(index_data_rows, columns=["index_name", "date", "closing_price"])
        df_indices['date'] = pd.to_datetime(df_indices['date'])
        
        # Pivot to have dates as index and index names as columns
        df_pivoted = df_indices.pivot(index='date', columns='index_name', values='closing_price').fillna(method='ffill')
        
        # Calculate daily log returns
        df_returns = np.log(df_pivoted / df_pivoted.shift(1)).dropna()
        
        # Construct Factors
        # Mkt-Rf (assuming Rf = 0 for daily simplicity)
        df_returns['Mkt'] = df_returns['NIFTY 50']
        
        # SMB: Small Minus Big
        if 'NIFTY SMALLCAP 250' in df_returns.columns and 'NIFTY 50' in df_returns.columns:
            df_returns['SMB'] = df_returns['NIFTY SMALLCAP 250'] - df_returns['NIFTY 50']
        else:
            df_returns['SMB'] = 0.0
            
        # HML: High Minus Low (Value factor)
        if 'NIFTY 50 VALUE 20' in df_returns.columns and 'NIFTY 50' in df_returns.columns:
            df_returns['HML'] = df_returns['NIFTY 50 VALUE 20'] - df_returns['NIFTY 50']
        else:
            df_returns['HML'] = 0.0
            
        factors = df_returns[['Mkt', 'SMB', 'HML']]
        
        # 2. Fetch Fund NAVs
        fund_nav_rows = await conn.fetch("""
            SELECT amfi_code, nav, nav_date
            FROM fund_history
            WHERE nav_date >= $1
            ORDER BY amfi_code, nav_date ASC
        """, df_returns.index.min())
        
        if not fund_nav_rows:
            logger.warning("⚠️ No fund history found for attribution analysis.")
            return

        df_funds = pd.DataFrame(fund_nav_rows, columns=["amfi_code", "nav", "date"])
        df_funds['date'] = pd.to_datetime(df_funds['date'])
        
        unique_funds = df_funds['amfi_code'].unique()
        logger.info(f"Analyzing {len(unique_funds)} funds...")

        for amfi in unique_funds:
            try:
                fund_data = df_funds[df_funds['amfi_code'] == amfi].set_index('date')['nav'].sort_index()
                if len(fund_data) < 60: # Minimum data points for regression
                    continue
                    
                fund_returns = np.log(fund_data / fund_data.shift(1)).dropna()
                
                # Align fund returns with factor returns
                aligned_data = pd.concat([fund_returns, factors], axis=1).dropna()
                
                if len(aligned_data) < 60:
                    continue
                
                X = aligned_data[['Mkt', 'SMB', 'HML']]
                y = aligned_data.iloc[:, 0] # First column is fund returns
                
                model = LinearRegression()
                model.fit(X, y)
                
                alpha = model.intercept_ * 252 # Annualized Alpha
                beta_mkt = model.coef_[0]
                beta_smb = model.coef_[1]
                beta_hml = model.coef_[2]
                r_squared = model.score(X, y)
                
                # 3. Update DB
                update_query = """
                    UPDATE fund_conviction_metrics
                    SET alpha = $1,
                        beta_mkt = $2,
                        beta_smb = $3,
                        beta_hml = $4,
                        r_squared = $5
                    WHERE LTRIM(amfi_code, '0') = $6
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE LTRIM(amfi_code, '0') = $7)
                """
                await conn.execute(update_query, 
                    float(alpha), float(beta_mkt), float(beta_smb), float(beta_hml), float(r_squared),
                    amfi.lstrip('0'), amfi.lstrip('0')
                )
                
            except Exception as e:
                logger.error(f"Error attributing fund {amfi}: {e}")

    logger.info("✅ Attribution analysis complete.")
