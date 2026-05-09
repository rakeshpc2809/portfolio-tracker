import yfinance as yf
from confluent_kafka import Producer
import json
import time
import logging
import os

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
TOPIC = "market.prices"

producer_config = {
    'bootstrap.servers': KAFKA_BOOTSTRAP_SERVERS,
    'client.id': 'ticker-plant-producer'
}

try:
    producer = Producer(producer_config)
except Exception as e:
    logger.error(f"Failed to initialize Kafka Producer: {e}")
    producer = None

def fetch_and_publish(tickers):
    if not producer: return
    
    logger.info(f"Fetching prices for {tickers}")
    try:
        data = yf.download(tickers, period="1d", interval="1m", progress=False)
        if data.empty: return

        for ticker in tickers:
            # Get latest price
            latest_price = float(data['Close'][ticker].iloc[-1])
            prev_close = float(data['Close'][ticker].iloc[-2]) if len(data) > 1 else latest_price
            
            payload = {
                "ticker": ticker,
                "price": latest_price,
                "change": latest_price - prev_close,
                "timestamp": time.time()
            }
            
            producer.produce(TOPIC, value=json.dumps(payload), key=ticker)
            logger.info(f"Published {ticker}: {latest_price}")
        
        producer.flush()
    except Exception as e:
        logger.error(f"Ticker plant error: {e}")

if __name__ == "__main__":
    # Mock tickers for demonstration
    TICKERS = ["^NSEI", "^NSMIDCP150", "GC=F", "RELIANCE.NS", "TCS.NS"]
    while True:
        fetch_and_publish(TICKERS)
        time.sleep(60) # Fetch every minute (yfinance limit for free tier)
