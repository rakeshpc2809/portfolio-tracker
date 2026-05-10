# cas-parser/broker_parser.py
import io, logging
from datetime import datetime
from typing import Any
import pandas as pd

logger = logging.getLogger(__name__)

# Column name normalisation map (handles different INDmoney CSV versions)
INDMONEY_COL_MAP = {
    "trade date":        "transaction_date",
    "symbol":            "ticker",
    "isin":              "isin",
    "exchange":          "exchange",
    "transaction type":  "transaction_type",
    "type":              "transaction_type",
    "quantity":          "quantity",
    "qty":               "quantity",
    "price":             "price_per_share",
    "value":             "gross_value",
    "brokerage":         "brokerage",
    "stt":               "stt",
    "net amount":        "total_amount",
    "net":               "total_amount",
}

def parse_indmoney_csv(file_content: str | bytes) -> list[dict[str, Any]]:
    """
    Parse INDmoney tradebook CSV. Handles both str and bytes input.
    Returns list of normalised transaction dicts ready for DB insertion.
    """
    if isinstance(file_content, bytes):
        file_content = file_content.decode("utf-8", errors="replace")

    df = pd.read_csv(io.StringIO(file_content), thousands=",")
    df.columns = [c.strip().lower() for c in df.columns]
    rename = {k: v for k, v in INDMONEY_COL_MAP.items() if k in df.columns}
    df = df.rename(columns=rename)

    required = {"transaction_date", "ticker", "isin", "transaction_type", "quantity", "price_per_share"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"INDmoney CSV missing columns: {missing}")

    records = []
    for _, row in df.iterrows():
        try:
            tx_type = str(row["transaction_type"]).strip().upper()
            if tx_type not in {"BUY", "SELL", "BONUS", "SPLIT", "RIGHTS", "DIVIDEND"}:
                continue

            # Parse date — INDmoney uses DD-MMM-YYYY or YYYY-MM-DD
            raw = str(row["transaction_date"]).strip()
            try:
                tx_date = datetime.strptime(raw, "%d-%b-%Y").date()
            except ValueError:
                tx_date = datetime.strptime(raw, "%Y-%m-%d").date()

            qty       = float(str(row["quantity"]).replace(",", ""))
            price     = float(str(row["price_per_share"]).replace(",", ""))
            brokerage = float(str(row.get("brokerage", 0) or 0).replace(",", ""))
            stt       = float(str(row.get("stt", 0) or 0).replace(",", ""))
            total     = float(str(row.get("total_amount", qty * price) or qty * price).replace(",", ""))

            records.append({
                "transaction_date": tx_date,
                "ticker":           str(row["ticker"]).strip().upper(),
                "isin":             str(row["isin"]).strip().upper(),
                "exchange":         str(row.get("exchange", "NSE")).strip().upper(),
                "transaction_type": tx_type,
                "quantity":         qty,
                "price_per_share":  price,
                "brokerage":        brokerage,
                "stt":              stt,
                "total_amount":     total,
                "source":           "INDMONEY_CSV",
            })
        except Exception as e:
            logger.error(f"Row parse error: {e} | row: {dict(row)}")

    logger.info(f"Parsed {len(records)} transactions from INDmoney CSV")
    return records


def parse_cdsl_statement(file_content: str | bytes) -> list[dict[str, Any]]:
    """Parse CDSL Transaction Statement CSV (authoritative depository data)."""
    if isinstance(file_content, bytes):
        file_content = file_content.decode("utf-8", errors="replace")
    df = pd.read_csv(io.StringIO(file_content))
    df.columns = [c.strip().lower() for c in df.columns]
    records = []
    for _, row in df.iterrows():
        try:
            debit  = float(str(row.get("debit qty", 0) or 0).replace(",", ""))
            credit = float(str(row.get("credit qty", 0) or 0).replace(",", ""))
            qty    = debit if debit > 0 else credit
            if qty == 0:
                continue
            tx_type = "BUY" if debit > 0 else "SELL"
            price   = float(str(row.get("rate", 0) or 0).replace(",", ""))
            records.append({
                "transaction_date": datetime.strptime(str(row["date"]).strip(), "%d-%b-%Y").date(),
                "isin":             str(row.get("isin", "")).strip().upper(),
                "ticker":           str(row.get("company", "")).strip().upper(),
                "exchange":         "NSE",
                "transaction_type": tx_type,
                "quantity":         qty,
                "price_per_share":  price,
                "brokerage":        0.0,
                "stt":              0.0,
                "total_amount":     qty * price,
                "source":           "CDSL",
            })
        except Exception as e:
            logger.error(f"CDSL row error: {e}")
    return records
