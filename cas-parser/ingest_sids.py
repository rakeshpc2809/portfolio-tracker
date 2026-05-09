import sys
import os
from rag_engine import initialize_rag_engine
from langchain.docstore.document import Document
from langchain.text_splitter import RecursiveCharacterTextSplitter

def ingest_document(file_path: str):
    """
    Ingests a single document into the Qdrant vector store.
    """
    if not os.path.exists(file_path):
        print(f"❌ File not found: {file_path}")
        return

    print(f"📄 Reading document: {file_path}")
    
    # Simple text loader (can be expanded for PDF/DOCX)
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"❌ Failed to read file: {e}")
        return

    # Initialize RAG engine
    vector_store = initialize_rag_engine()
    
    # Split text into chunks for better retrieval
    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=1000,
        chunk_overlap=100,
        length_function=len,
    )
    
    chunks = text_splitter.split_text(content)
    print(f"✂️ Split into {len(chunks)} chunks")
    
    # Convert to LangChain Documents
    documents = [
        Document(page_content=chunk, metadata={"source": file_path, "chunk": i})
        for i, chunk in enumerate(chunks)
    ]
    
    # Add to vector store
    print(f"📤 Uploading to Qdrant...")
    vector_store.add_documents(documents)
    print(f"✅ Successfully ingested {file_path}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python ingest_sids.py <path_to_sid_file>")
        sys.exit(1)
        
    target_file = sys.argv[1]
    ingest_document(target_file)
