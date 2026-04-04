from nsepython import index_pe_pb_div
import pandas as pd
from sqlalchemy import create_engine, text
from datetime import datetime, timedelta

# 1. Database Connection
engine = create_engine('postgresql://user:password@postgres:5432/cas_db')

def get_target_indices():
    """Fetch unique benchmark indices from the scheme table."""
    try:
        with engine.connect() as conn:
            query = text("SELECT DISTINCT benchmark_index FROM scheme WHERE benchmark_index IS NOT NULL")
            result = conn.execute(query)
            indices = [row[0] for row in result]
            
            if not indices:
                return ["NIFTY 50", "NIFTY 500", "NIFTY MIDCAP 150"]
            return indices
    except Exception as e:
        print(f"Error fetching indices from DB: {e}")
        return ["NIFTY 50", "NIFTY 500", "NIFTY MIDCAP 150"]

def sync_market_data():
    target_indices = get_target_indices()
    print(f"Target Indices to sync: {target_indices}")
    
    # Dynamic date range: last 15 days to capture weekends/holidays
    now = datetime.now()
    end_date_str = now.strftime("%d-%b-%Y")
    start_date_str = (now - timedelta(days=15)).strftime("%d-%b-%Y")
    
    all_data = []
    
    for index in target_indices:
        print(f"Fetching data for {index} from {start_date_str} to {end_date_str}...")
        try:
            df = index_pe_pb_div(index, start_date_str, end_date_str)
            if df is not None and not df.empty:
                # Ensure the date column is parsed and sorted to get the latest available data
                date_col_temp = next((c for c in df.columns if 'date' in c.lower()), None)
                if date_col_temp:
                    df[date_col_temp] = pd.to_datetime(df[date_col_temp])
                    df = df.sort_values(by=date_col_temp)
                
                # Take the latest row (most recent date)
                latest_row = df.tail(1).copy()
                all_data.append(latest_row)
            else:
                print(f"No data returned for {index}")
        except Exception as e:
            print(f"Skipping {index}: {e}")

    if all_data:
        final_df = pd.concat(all_data)
        
        # Clean up columns for the index_fundamentals table
        # We rename them to match the index_fundamentals table schema
        final_df.columns = [c.lower().replace(' ', '_') for c in final_df.columns]
        print(f"Cleaned Columns: {final_df.columns.tolist()}")
        
        try:
            # Push to a temp table
            final_df.to_sql('index_fundamentals_temp', engine, if_exists='replace', index=False)

            # Identify columns correctly
            col_list = final_df.columns.tolist()
            # nsepython usually returns: Index Name, Date, PE, PB, Div Yield
            # After cleaning: index_name, date, pe, pb, div_yield (or dividend_yield)
            
            # Find the actual dividend yield column name
            dy_col = next((c for c in col_list if 'yield' in c or 'div' in c), 'div_yield')
            pe_col = next((c for c in col_list if 'pe' in c), 'pe')
            pb_col = next((c for c in col_list if 'pb' in c), 'pb')
            date_col = next((c for c in col_list if 'date' in c), 'date')

            with engine.connect() as conn:
                upsert_query = text(f"""
                    INSERT INTO index_fundamentals (index_name, date, pe, pb, div_yield)
                    SELECT 
                        index_name, 
                        CAST({date_col} AS DATE), 
                        CAST({pe_col} AS DOUBLE PRECISION), 
                        CAST({pb_col} AS DOUBLE PRECISION), 
                        CAST({dy_col} AS DOUBLE PRECISION)
                    FROM index_fundamentals_temp
                    ON CONFLICT (index_name) DO UPDATE 
                    SET date = EXCLUDED.date,
                        pe = EXCLUDED.pe,
                        pb = EXCLUDED.pb,
                        div_yield = EXCLUDED.div_yield;
                """)
                conn.execute(upsert_query)
                conn.commit()
                print("✅ Database updated with latest benchmark valuations.")
        except Exception as e:
            print(f"Error during database update: {e}")

if __name__ == "__main__":
    sync_market_data()
