import os
import time
import json
import requests
import yfinance as yf
from datetime import date
from sqlalchemy import create_engine, Column, Integer, String, Float, Date, UniqueConstraint, text
from sqlalchemy.orm import declarative_base, sessionmaker
from sqlalchemy.dialects.postgresql import insert

# Configuration
DB_URL = os.getenv('DATABASE_URL', 'postgresql://user:password@localhost:5432/cas_db')
spring_db_url = os.getenv('SPRING_DATASOURCE_URL', '')
if spring_db_url.startswith('jdbc:postgresql://'):
    parts = spring_db_url.split('jdbc:postgresql://')[1].split('?')[0]
    username = os.getenv('SPRING_DATASOURCE_USERNAME', 'user')
    password = os.getenv('SPRING_DATASOURCE_PASSWORD', 'password')
    DB_URL = f"postgresql://{username}:{password}@{parts}"

ISIN_MAP_FILE = os.path.join(os.path.dirname(__file__), 'isin_ticker_map.json')
MFDATA = 'https://mfdata.in/api/v1'
MFAPI  = 'https://api.mfapi.in/mf'

# Database Setup
engine = create_engine(DB_URL)
Base = declarative_base()

class FundMetric(Base):
    __tablename__ = 'fund_metrics'
    id = Column(Integer, primary_key=True)
    fetch_date = Column(Date, nullable=False)
    scheme_code = Column(String(50), nullable=False)
    scheme_name = Column(String(255))
    nav = Column(Float)
    pe_ratio = Column(Float)
    pb_ratio = Column(Float)
    expense_ratio = Column(Float)
    aum_cr = Column(Float)
    coverage_pct = Column(Float)
    holdings_as_of = Column(Date)
    __table_args__ = (UniqueConstraint('fetch_date', 'scheme_code', name='uq_fetch_date_scheme_code'),)

class HoldingsSnapshot(Base):
    __tablename__ = 'holdings_snapshot'
    id = Column(Integer, primary_key=True)
    fetch_date = Column(Date, nullable=False)
    scheme_code = Column(String(50), nullable=False)
    stock_name = Column(String(255))
    ticker = Column(String(50))
    weight_pct = Column(Float)
    stock_pe = Column(Float)
    stock_pb = Column(Float)

Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

# ISIN Mapping Utilities
def load_isin_map():
    if os.path.exists(ISIN_MAP_FILE):
        try:
            with open(ISIN_MAP_FILE, 'r') as f:
                return json.load(f)
        except: return {}
    return {}

def save_isin_map(mapping):
    with open(ISIN_MAP_FILE, 'w') as f:
        json.dump(mapping, f, indent=4)

_isin_map = load_isin_map()
_ratio_cache = {}

def get_ticker_by_isin(isin, stock_name):
    if isin and isin in _isin_map: return _isin_map[isin]

    # 1. Try searching Yahoo Finance by ISIN (Most reliable)
    if isin:
        try:
            search = yf.Search(isin, max_results=1)
            if search.quotes:
                ticker = search.quotes[0]['symbol']
                _isin_map[isin] = ticker
                save_isin_map(_isin_map)
                return ticker
        except: pass

    # 2. Try searching by Stock Name
    if stock_name:
        try:
            clean_name = stock_name.replace(' Ltd', '').replace(' Limited', '').split(' - ')[0].strip()
            search = yf.Search(clean_name, max_results=10)
            if search.quotes:
                for q in search.quotes:
                    symbol = q.get('symbol', '')
                    exch = q.get('exchange', '')
                    if exch in ['NSI', 'BSE'] or symbol.endswith('.NS') or symbol.endswith('.BO'):
                        if isin: # Only cache in persistent map if we have a stable ISIN
                            _isin_map[isin] = symbol
                            save_isin_map(_isin_map)
                        return symbol
                return search.quotes[0]['symbol']
        except: pass

    return None

def fetch_batch_ratios(tickers):
    """Fetch PE/PB for a list of tickers efficiently with anti-rate-limiting."""
    import random
    remaining = [t for t in tickers if t not in _ratio_cache]
    
    if not remaining:
        return {t: _ratio_cache[t] for t in tickers}

    print(f"   Fetching data for {len(remaining)} unique tickers from Yahoo Finance...")
    for i in range(0, len(remaining), 20):
        chunk = remaining[i:i+20]
        max_retries = 3
        backoff = 2
        
        for attempt in range(max_retries):
            try:
                data = yf.Tickers(' '.join(chunk))
                for t in chunk:
                    try:
                        info = data.tickers[t].info
                        pe = info.get('trailingPE') or info.get('forwardPE')
                        pb = info.get('priceToBook')
                        _ratio_cache[t] = (
                            round(float(pe), 2) if pe else None,
                            round(float(pb), 2) if pb else None
                        )
                    except:
                        _ratio_cache[t] = (None, None)
                break # Success, move to next chunk
            except Exception as e:
                if attempt < max_retries - 1:
                    wait_time = backoff ** attempt + random.uniform(1, 3)
                    print(f"      Rate-limited? Retrying in {wait_time:.1f}s... (Error: {e})")
                    time.sleep(wait_time)
                else:
                    print(f"      Failed chunk after {max_retries} attempts: {e}")
                    for t in chunk: _ratio_cache[t] = (None, None)
        
        # Random sleep between chunks: 1 to 3 seconds
        time.sleep(random.uniform(1.0, 3.0))

    return {t: _ratio_cache[t] for t in tickers}

def weighted_harmonic_mean(values_weights):
    """
    Correct formula: Total_Weight / Sum(weight / ratio)
    Filters out negative or zero ratios and rebases weights.
    """
    valid = [(r, w) for r, w in values_weights if r is not None and r > 0 and w is not None and w > 0]
    if not valid: return None
    
    total_w = sum(w for _, w in valid)
    # Rebase weights so they sum to 100 (or keep proportions correct)
    # The Harmonic Mean naturally handles this as long as we use the rebased total_w
    denom = sum(w / r for r, w in valid)
    return round(total_w / denom, 2) if denom else None

def compute_fund_ratios(holdings: list) -> dict:
    total_w = 0
    ticker_to_weight = {}
    ticker_to_name = {}
    
    print(f"      Processing {len(holdings)} holdings...")
    # 1. Map all holdings to Yahoo tickers
    for h in holdings:
        weight = float(h.get('weight_pct', 0)) if h.get('weight_pct') else 0
        if weight <= 0: continue
        
        isin = h.get('isin')
        stock_name = h.get('stock_name', '')
        ticker = get_ticker_by_isin(isin, stock_name)
        
        total_w += weight
        if ticker:
            ticker_to_weight[ticker] = ticker_to_weight.get(ticker, 0) + weight
            ticker_to_name[ticker] = stock_name
        else:
            print(f"      Could not find ticker for: {stock_name} (ISIN: {isin})")

    print(f"      Total weight identified: {total_w}, Tickers found: {len(ticker_to_weight)}")
    # 2. Batch fetch ratios
    unique_tickers = list(ticker_to_weight.keys())
    ratios_data = fetch_batch_ratios(unique_tickers)

    # 3. Compute weighted harmonic mean
    pe_pairs, pb_pairs = [], []
    detail = []
    
    for ticker, (pe, pb) in ratios_data.items():
        w = ticker_to_weight[ticker]
        if pe: pe_pairs.append((pe, w))
        if pb: pb_pairs.append((pb, w))
        detail.append({
            'ticker': ticker, 
            'weight': w, 
            'pe': pe, 
            'pb': pb, 
            'stock_name': ticker_to_name[ticker]
        })

    covered = sum(w for _, w in pe_pairs)
    coverage = round(covered / total_w * 100, 1) if total_w else 0

    return {
        'pe_ratio': weighted_harmonic_mean(pe_pairs),
        'pb_ratio': weighted_harmonic_mean(pb_pairs),
        'coverage_pct': coverage,
        'holdings_detail': detail
    }

def get_watchlist(engine):
    try:
        with engine.connect() as conn:
            query = text("SELECT DISTINCT amfi_code, name FROM scheme WHERE amfi_code IS NOT NULL")
            result = conn.execute(query)
            return {row[0]: row[1] for row in result}
    except: return {}

def get_holdings(amfi_code: str) -> tuple:
    """Returns (holdings_list, as_of_date)"""
    try:
        r = requests.get(f'{MFDATA}/schemes/{amfi_code}/holdings', timeout=10)
        res = r.json()
        if res.get('status') == 'success':
            data = res.get('data', {})
            # mfdata.in usually returns month like '2026-03' in the holdings response
            as_of_str = data.get('month', '')
            as_of_date = None
            if as_of_str:
                from datetime import datetime
                try: as_of_date = datetime.strptime(as_of_str, '%Y-%m').date()
                except: pass
            return data.get('equity_holdings', []), as_of_date
        
        # Fallback to family holdings
        r_detail = requests.get(f'{MFDATA}/schemes/{amfi_code}', timeout=10)
        d_res = r_detail.json()
        if d_res.get('status') == 'success':
            fid = d_res.get('data', {}).get('family_id')
            if fid:
                r2 = requests.get(f'{MFDATA}/families/{fid}/holdings', timeout=10)
                res2 = r2.json()
                if res2.get('status') == 'success':
                    data2 = res2.get('data', {})
                    as_of_str = data2.get('month', '')
                    as_of_date = None
                    if as_of_str:
                        from datetime import datetime
                        try: as_of_date = datetime.strptime(as_of_str, '%Y-%m').date()
                        except: pass
                    return data2.get('equity_holdings', []), as_of_date
    except Exception as e:
        print(f"      Holdings fetch error: {e}")
    return [], None

def daily_fetch():
    session = Session()
    today = date.today()
    watchlist = get_watchlist(engine)
    
    print(f"\n{'='*60}")
    print(f"MF Metrics Engine v2.0 - Starting run for {today}")
    print(f"{'='*60}")

    for code, name in watchlist.items():
        print(f"\n-> {name} ({code})")
        try:
            # 1. Fetch NAV and check API ratios first
            r_detail = requests.get(f'{MFDATA}/schemes/{code}', timeout=10)
            d_res = r_detail.json()
            
            nav, pe, pb, coverage = None, None, None, 0.0
            holdings_as_of = None
            computed = False

            if d_res.get('status') == 'success':
                d = d_res.get('data', {})
                nav = d.get('nav')
                val = d.get('ratios', {}).get('valuation', {})
                pe, pb = val.get('pe_ratio'), val.get('pb_ratio')
                
                # Validation: Discard if PE is < 8.0 or > 150.0 (Sanity Checks)
                if pe and 8.0 < pe < 150.0: 
                    coverage = 100.0
                    print(f"   Using API Ratios: PE={pe}, PB={pb}")
                else:
                    if pe: print(f"   ! API Data Outlier (PE={pe}). Triggering fallback.")
                    pe, pb = None, None # Reset for computation

            # 2. Compute if API missing or unreliable
            if pe is None:
                holdings, holdings_as_of = get_holdings(code)
                if holdings:
                    res = compute_fund_ratios(holdings)
                    pe, pb, coverage = res['pe_ratio'], res['pb_ratio'], res['coverage_pct']
                    computed = True
                    print(f"   Computed Ratios: PE={pe}, PB={pb}, Coverage={coverage}%, AsOf={holdings_as_of}")

            # 3. Final Reliability Check: Coverage must be > 75% for Equity funds
            if pe and coverage < 75.0:
                print(f"   ! DATA REJECTED: Coverage too low ({coverage}%)")
                pe, pb = None, None

            # 4. Upsert
            stmt = insert(FundMetric).values(
                fetch_date=today, scheme_code=code, scheme_name=name,
                nav=nav, pe_ratio=pe, pb_ratio=pb, coverage_pct=coverage,
                holdings_as_of=holdings_as_of
            )
            on_conflict = stmt.on_conflict_do_update(
                constraint='uq_fetch_date_scheme_code',
                set_={'nav': stmt.excluded.nav, 'pe_ratio': stmt.excluded.pe_ratio,
                      'pb_ratio': stmt.excluded.pb_ratio, 'coverage_pct': stmt.excluded.coverage_pct,
                      'holdings_as_of': stmt.excluded.holdings_as_of}
            )
            session.execute(on_conflict)

            if computed and res.get('holdings_detail'):
                for h in res['holdings_detail']:
                    session.add(HoldingsSnapshot(
                        fetch_date=today, scheme_code=code, stock_name=h['stock_name'],
                        ticker=h['ticker'], weight_pct=h['weight'], stock_pe=h['pe'], stock_pb=h['pb']
                    ))
            session.commit()
        except Exception as e:
            session.rollback()
            print(f"   ERROR: {e}")

    session.close()
    print(f"\n{'='*60}\nDone. Metrics sync complete.\n{'='*60}")

if __name__ == "__main__":
    daily_fetch()
