import logging
try:
    from transformers import pipeline
    TRANSFORMERS_AVAILABLE = True
except ImportError:
    TRANSFORMERS_AVAILABLE = False

try:
    from qdrant_client import QdrantClient
    from qdrant_client.http import models
    QDRANT_AVAILABLE = True
except ImportError:
    QDRANT_AVAILABLE = False

import os
import uuid
from datetime import datetime

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Qdrant Configuration
QDRANT_HOST = os.getenv("QDRANT_HOST", "cas_qdrant")
QDRANT_PORT = int(os.getenv("QDRANT_PORT", 6333))
COLLECTION_NAME = "market_sentiment"

# Lazy-loaded globals
_qdrant = None
_sentiment_task = None

def get_qdrant():
    global _qdrant
    if _qdrant is None:
        if not QDRANT_AVAILABLE:
            logger.warning("Qdrant client not installed. Vector features disabled.")
            _qdrant = "FAILED"
            return None
        try:
            logger.info(f"Connecting to Qdrant at {QDRANT_HOST}:{QDRANT_PORT}...")
            _qdrant = QdrantClient(host=QDRANT_HOST, port=QDRANT_PORT, timeout=5)
            # Ensure collection exists
            collections = _qdrant.get_collections().collections
            if not any(c.name == COLLECTION_NAME for c in collections):
                _qdrant.create_collection(
                    collection_name=COLLECTION_NAME,
                    vectors_config=models.VectorParams(size=384, distance=models.Distance.COSINE),
                )
                logger.info(f"Created Qdrant collection: {COLLECTION_NAME}")
        except Exception as e:
            logger.error(f"Failed to initialize Qdrant: {e}")
            _qdrant = "FAILED" # Marker to avoid repeated attempts
    return _qdrant if _qdrant != "FAILED" else None

def get_sentiment_task():
    global _sentiment_task
    if _sentiment_task is None:
        if not TRANSFORMERS_AVAILABLE:
            logger.warning("transformers not installed. Sentiment features disabled.")
            _sentiment_task = "FAILED"
            return None
        try:
            logger.info("Initializing FinBERT sentiment model (this may take a minute)...")
            _sentiment_task = pipeline("sentiment-analysis", model="ProsusAI/finbert")
            logger.info("Loaded FinBERT sentiment model successfully")
        except Exception as e:
            logger.error(f"Failed to load sentiment model: {e}")
            _sentiment_task = "FAILED"
    return _sentiment_task if _sentiment_task != "FAILED" else None

def analyze_sentiment(text: str, metadata: dict = None):
    """
    Analyzes sentiment of a given text and stores it in Qdrant.
    """
    task = get_sentiment_task()
    db = get_qdrant()
    
    if not task:
        return {"error": "Sentiment service unavailable"}

    try:
        # 1. Get Sentiment Score
        result = task(text)[0]
        label = result['label']
        score = result['score']

        signal = {
            "id": str(uuid.uuid4()),
            "text": text,
            "sentiment": label,
            "confidence": score,
            "timestamp": datetime.now().isoformat(),
            "metadata": metadata or {}
        }
        
        logger.info(f"Analyzed Signal: {label} ({score:.2f}) - {text[:50]}...")
        return signal
        
    except Exception as e:
        logger.error(f"Sentiment analysis failed: {e}")
        return {"error": str(e)}

def get_latest_signals(limit: int = 10):
    return []
