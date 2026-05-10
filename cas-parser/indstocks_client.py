# cas-parser/indstocks_client.py
import os, time, httpx, asyncio, logging
from datetime import datetime
from pydantic import BaseModel

logger = logging.getLogger(__name__)

BASE_URL     = "https://api.indstocks.com"
ACCESS_TOKEN = os.environ.get("INDSTOCKS_ACCESS_TOKEN", "")
# Token expires every 24h — regenerate at indstocks.com → API section
# Static IP must be whitelisted first

HEADERS = {"Authorization": ACCESS_TOKEN, "Content-Type": "application/json"}

class Holding(BaseModel):
    security_id:        str
    trading_symbol:     str
    exchange_segment:   str
    isin:               str
    quantity:           int
    average_price:      float
    last_traded_price:  float
    close_price:        float
    market_value:       float
    pnl_absolute:       float
    pnl_percent:        float

class OHLCVCandle(BaseModel):
    timestamp:  int
    open:       float
    high:       float
    low:        float
    close:      float
    volume:     int

async def get_holdings() -> list[Holding]:
    """
    GET /portfolio/holdings
    Returns current Demat positions.
    NOTE: Returns average_price only — NO individual buy dates.
    Use CSV import for FIFO tax lot tracking.
    """
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.get(f"{BASE_URL}/portfolio/holdings", headers=HEADERS)
        r.raise_for_status()
        data = r.json()
        if data.get("status") != "success":
            raise ValueError(f"INDstocks error: {data}")
        return [Holding(**h) for h in data["data"]]

async def get_ltp(scrip_codes: list[str]) -> dict[str, float]:
    """
    GET /market/quotes/ltp
    scrip_code format: 'NSE_{security_id}'  e.g. 'NSE_3045'
    Returns { 'NSE_3045': 2505.10, ... }
    """
    codes = ",".join(scrip_codes)
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.get(f"{BASE_URL}/market/quotes/ltp",
                        headers=HEADERS, params={"scrip-codes": codes})
        r.raise_for_status()
        return {k: v["ltp"] for k, v in r.json().get("data", {}).items()}

async def get_historical_eod(security_id: str, exchange: str = "NSE",
                               days: int = 365) -> list[OHLCVCandle]:
    """
    GET /market/historical/1day
    Max range: 1 year. For older history use NSE bhavcopy archive.
    security_id: INDstocks internal ID (from holdings response)
    """
    scrip_code = f"{exchange}_{security_id}"
    end_ms   = int(time.time() * 1000)
    start_ms = end_ms - (days * 86_400_000)

    async with httpx.AsyncClient(timeout=30) as c:
        r = await c.get(f"{BASE_URL}/market/historical/1day", headers=HEADERS,
                        params={"scrip-codes": scrip_code,
                                "start_time":   start_ms,
                                "end_time":     end_ms})
        r.raise_for_status()
        candles = r.json().get("data", {}).get("candles", [])
        return [OHLCVCandle(timestamp=c[0], open=c[1], high=c[2],
                            low=c[3], close=c[4], volume=c[5])
                for c in candles]

async def backfill_all_prices(pool, stocks: list[dict]):
    """Backfill 1-year EOD prices for all stocks. Respects 10 req/sec limit."""
    async with pool.acquire() as conn:
        for i, stock in enumerate(stocks):
            try:
                candles = await get_historical_eod(stock["security_id"],
                                                   stock["exchange"], days=365)
                rows = [(stock["ticker"],
                         datetime.fromtimestamp(c.timestamp / 1000).date(),
                         c.open, c.high, c.low, c.close, c.volume)
                        for c in candles]
                await conn.executemany("""
                    INSERT INTO stock_price_eod
                        (ticker, price_date, open_price, high_price, low_price, close_price, volume)
                    VALUES ($1,$2,$3,$4,$5,$6,$7)
                    ON CONFLICT (ticker, price_date) DO UPDATE
                        SET close_price = EXCLUDED.close_price
                """, rows)
                logger.info(f"  ✅ {stock['ticker']}: {len(rows)} candles backfilled")
                if (i + 1) % 10 == 0:
                    await asyncio.sleep(1.0)  # rate limit: 10/sec
            except Exception as e:
                logger.error(f"  ❌ {stock['ticker']}: {e}")
