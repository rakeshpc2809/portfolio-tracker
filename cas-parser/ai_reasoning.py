import os
import logging
import httpx
from typing import Dict, Any

logger = logging.getLogger(__name__)

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://cas_ollama:11434")
MODEL_NAME = os.getenv("AI_MODEL", "phi3.5")

async def check_ai_health() -> dict:
    """Diagnostic tool to verify AI Engine connectivity."""
    urls_to_try = ["http://cas_ollama:11434", "http://ollama:11434", "http://172.20.0.2:11434"]
    errors = []
    for url in urls_to_try:
        try:
            async with httpx.AsyncClient() as client:
                res = await client.get(f"{url}/api/tags", timeout=2.0)
                if res.status_code == 200:
                    return {"status": "connected", "url": url, "models": res.json()}
        except Exception as e:
            errors.append(f"{url}: {str(e)}")
    return {"status": "disconnected", "errors": errors}

async def generate_portfolio_reasoning(
    fund_name: str, 
    current_weight: float, 
    target_weight: float, 
    conviction: float, 
    regime: str
) -> str:
    """Generates human-like financial reasoning using a local LLM (Gemma 2)."""
    
    delta = target_weight - current_weight
    action = "increase" if delta > 0 else "decrease"
    
    prompt = f"""
    You are an elite AI Wealth Manager. Analyze this portfolio adjustment and provide a concise, professional reasoning in 15 words or less.
    
    Fund: {fund_name}
    Action: {action} weight from {current_weight}% to {target_weight}%
    Conviction Score: {conviction}/100
    Market Regime: {regime}
    
    Reasoning:
    """
    
    try:
        logger.info(f"Connecting to AI Engine at {OLLAMA_URL} using {MODEL_NAME}...")
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{OLLAMA_URL}/api/generate",
                json={
                    "model": MODEL_NAME,
                    "prompt": prompt,
                    "stream": False,
                    "options": {"temperature": 0.3}
                },
                timeout=60.0 # Increased for first-load latency
            )
            
            if response.status_code == 200:
                result = response.json()
                return result.get("response", "").strip().replace('"', '')
    except Exception:
        # --- Shadow Intelligence Fallback ---
        # Deterministic logic based on real portfolio math
        if regime == "BULLISH_HMM":
            if delta > 0:
                return f"Capitalizing on bullish regime. High conviction ({conviction}/100) supports aggressive position scaling."
            return "Bullish market detected. Trimming slightly to harvest gains while maintaining core exposure."
        else:
            if delta > 0:
                return f"Contrarian signal: Increasing weight during volatility to lower cost-basis at {conviction} conviction."
            return f"Defensive maneuver: Reducing weight to {target_weight}% to mitigate drawdown in current regime."
        
    return "Strategic rebalancing to optimize risk-adjusted returns."

async def generate_portfolio_chat_response(
    query: str,
    portfolio_summary: str,
    history: list = []
) -> str:
    """Handles natural language queries about the specific portfolio state."""
    
    system_prompt = f"""
    You are the 'System Nucleus' Wealth AI. You have full access to the user's financial portfolio.
    
    PORTFOLIO SNAPSHOT:
    {portfolio_summary}
    
    GUIDELINES:
    - Be concise, professional, and data-driven.
    - If asked about specific funds, use the XIRR and weight data provided.
    - If you don't know something, be honest.
    - Use Markdown for tables or lists if needed.
    """
    
    messages = [{"role": "system", "content": system_prompt}]
    for msg in history[-5:]: # Keep last 5 messages for context
        messages.append(msg)
    messages.append({"role": "user", "content": query})
    
    try:
        logger.info(f"Wealth AI attempting chat with {OLLAMA_URL} model {MODEL_NAME}")
        async with httpx.AsyncClient(timeout=300.0) as client:
            response = await client.post(
                f"{OLLAMA_URL}/api/chat",
                json={
                    "model": MODEL_NAME,
                    "messages": messages,
                    "stream": False,
                    "options": {"temperature": 0.5}
                }
            )
            
            if response.status_code == 200:
                result = response.json()
                return result.get("message", {}).get("content", "").strip()
    except Exception as e:
        logger.error(f"Chat failed: {e}")
        return "I'm currently recalibrating my neural links. Please try again in a moment."
    
    return "I am unable to process that query at the moment."
