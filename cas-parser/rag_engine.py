import os
from qdrant_client import QdrantClient
from qdrant_client.http import models
from langchain_community.vectorstores import Qdrant
from langchain_community.embeddings import HuggingFaceEmbeddings

# Configuration
QDRANT_HOST = os.getenv("QDRANT_HOST", "localhost")
QDRANT_PORT = int(os.getenv("QDRANT_PORT", 6333))
COLLECTION_NAME = "fund_sids"

def initialize_rag_engine():
    """
    Standalone initialization script for LangChain RAG capabilities.
    Connects to Qdrant, initializes the HuggingFace embedding model,
    and ensures the 'fund_sids' collection exists.
    """
    print(f"🚀 Initializing RAG Engine (Qdrant @ {QDRANT_HOST}:{QDRANT_PORT})...")
    
    client = QdrantClient(host=QDRANT_HOST, port=QDRANT_PORT)
    
    # Ensure collection exists
    collections = client.get_collections().collections
    exists = any(c.name == COLLECTION_NAME for c in collections)
    
    if not exists:
        print(f"📦 Creating collection: {COLLECTION_NAME}")
        client.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=models.VectorParams(size=384, distance=models.Distance.COSINE),
        )
    
    # Initialize embeddings model
    # all-MiniLM-L6-v2 produces 384-dimensional vectors
    embeddings = HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2")
    
    # Initialize LangChain VectorStore
    vector_store = Qdrant(
        client=client,
        collection_name=COLLECTION_NAME,
        embeddings=embeddings
    )
    
    print(f"✅ RAG Engine initialized for collection: {COLLECTION_NAME}")
    return vector_store

if __name__ == "__main__":
    initialize_rag_engine()
