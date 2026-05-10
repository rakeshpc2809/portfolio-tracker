# cas-parser/stock_lot_engine.py
import logging
from datetime import date
from decimal import Decimal, ROUND_HALF_UP

logger = logging.getLogger(__name__)

# Configurable — move to env/config in production
LTCG_RATE      = Decimal("0.125")   # 12.5% post July 2024 budget
STCG_RATE      = Decimal("0.20")    # 20%
LTCG_EXEMPTION = Decimal("125000")  # ₹1.25L per FY — update if budget changes

def _tax_category(buy_date: date, sell_date: date) -> str:
    """Equity: held > 12 months = LTCG, else STCG."""
    return "LTCG" if (sell_date - buy_date).days > 365 else "STCG"

def _current_fy(ref_date: date) -> str:
    if ref_date.month >= 4:
        return f"{ref_date.year}-{str(ref_date.year + 1)[2:]}"
    return f"{ref_date.year - 1}-{str(ref_date.year)[2:]}"


async def rebuild_lots_for_stock(conn, stock_id: int):
    """
    Rebuild all FIFO tax lots for a stock from its raw transactions.
    Call after CSV import or any manual transaction edit.
    This is the SINGLE SOURCE OF TRUTH for lot state.
    """
    # Clear and rebuild
    await conn.execute("DELETE FROM stock_capital_gain WHERE stock_id = $1", stock_id)
    await conn.execute("DELETE FROM stock_tax_lot WHERE stock_id = $1", stock_id)

    txns = await conn.fetch("""
        SELECT id, transaction_date, transaction_type, quantity, price_per_share,
               brokerage, stt, stamp_duty, other_charges
        FROM stock_transaction
        WHERE stock_id = $1 ORDER BY transaction_date ASC, id ASC
    """, stock_id)

    open_lots = []  # FIFO queue

    for tx in txns:
        tx_type = tx["transaction_type"]
        qty     = Decimal(str(tx["quantity"]))
        price   = Decimal(str(tx["price_per_share"]))

        if tx_type == "BUY":
            charges = sum(Decimal(str(tx.get(f, 0) or 0))
                         for f in ("brokerage", "stt", "stamp_duty", "other_charges"))
            cost_per_share = price + (charges / qty).quantize(Decimal("0.0001"), ROUND_HALF_UP)

            lot_id = await conn.fetchval("""
                INSERT INTO stock_tax_lot
                    (stock_id, buy_transaction_id, buy_date, original_qty,
                     remaining_qty, cost_basis_per_share, status)
                VALUES ($1,$2,$3,$4,$4,$5,'OPEN') RETURNING id
            """, stock_id, tx["id"], tx["transaction_date"], qty, cost_per_share)

            open_lots.append({"id": lot_id, "buy_date": tx["transaction_date"],
                              "remaining_qty": qty, "cost_basis": cost_per_share})

        elif tx_type == "SELL":
            sell_qty   = qty
            sell_date  = tx["transaction_date"]
            sell_price = price

            while sell_qty > 0 and open_lots:
                lot      = open_lots[0]
                consumed = min(sell_qty, lot["remaining_qty"])
                cat      = _tax_category(lot["buy_date"], sell_date)
                realized = (sell_price - lot["cost_basis"]) * consumed
                rate     = LTCG_RATE if cat == "LTCG" else STCG_RATE

                await conn.execute("""
                    INSERT INTO stock_capital_gain
                        (stock_id, buy_lot_id, sell_transaction_id, buy_date, sell_date,
                         quantity_sold, buy_price, sell_price, realized_gain,
                         gain_type, tax_rate, tax_estimate, fy)
                    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
                """, stock_id, lot["id"], tx["id"], lot["buy_date"], sell_date,
                    consumed, lot["cost_basis"], sell_price, realized,
                    cat, rate, realized * rate, _current_fy(sell_date))

                lot["remaining_qty"] -= consumed
                sell_qty             -= consumed

                if lot["remaining_qty"] <= Decimal("0.0001"):
                    await conn.execute(
                        "UPDATE stock_tax_lot SET remaining_qty=0, status='CLOSED' WHERE id=$1",
                        lot["id"])
                    open_lots.pop(0)
                else:
                    await conn.execute(
                        "UPDATE stock_tax_lot SET remaining_qty=$1, status='PARTIAL' WHERE id=$2",
                        lot["remaining_qty"], lot["id"])

        elif tx_type == "BONUS":
            # Bonus shares: zero cost, holding period starts from grant date
            lot_id = await conn.fetchval("""
                INSERT INTO stock_tax_lot
                    (stock_id, buy_transaction_id, buy_date, original_qty,
                     remaining_qty, cost_basis_per_share, adjusted_cost_basis, status)
                VALUES ($1,$2,$3,$4,$4,0,0,'OPEN') RETURNING id
            """, stock_id, tx["id"], tx["transaction_date"], qty)
            open_lots.append({"id": lot_id, "buy_date": tx["transaction_date"],
                              "remaining_qty": qty, "cost_basis": Decimal("0")})

        elif tx_type == "SPLIT":
            # quantity field = new shares per 1 old share (ratio)
            ratio = qty
            for lot in open_lots:
                lot["remaining_qty"] *= ratio
                lot["cost_basis"]     = (lot["cost_basis"] / ratio).quantize(Decimal("0.0001"))
            await conn.execute("""
                UPDATE stock_tax_lot
                SET remaining_qty          = remaining_qty * $1,
                    cost_basis_per_share   = cost_basis_per_share / $1,
                    adjusted_cost_basis    = cost_basis_per_share / $1
                WHERE stock_id = $2 AND status IN ('OPEN','PARTIAL')
            """, ratio, stock_id)

    logger.info(f"Rebuilt {len(open_lots)} open lots for stock_id={stock_id}")


def compute_unrealised(open_lots: list[dict], current_price: Decimal,
                       fy_ltcg_realized: Decimal = Decimal("0")) -> dict:
    """Compute unrealised LTCG / STCG breakdown for open lots."""
    today  = date.today()
    ltcg   = Decimal("0")
    stcg   = Decimal("0")
    cost   = Decimal("0")
    value  = Decimal("0")

    for lot in open_lots:
        qty      = Decimal(str(lot["remaining_qty"]))
        cb       = Decimal(str(lot["cost_basis_per_share"]))
        lot_val  = qty * current_price
        lot_cost = qty * cb
        gain     = max(Decimal("0"), lot_val - lot_cost)
        if _tax_category(lot["buy_date"], today) == "LTCG":
            ltcg += gain
        else:
            stcg += gain
        cost  += lot_cost
        value += lot_val

    ltcg_exempt  = max(Decimal("0"), LTCG_EXEMPTION - fy_ltcg_realized)
    ltcg_taxable = max(Decimal("0"), ltcg - ltcg_exempt)
    return {
        "total_invested":    float(cost),
        "total_value":       float(value),
        "unrealised_pnl":    float(value - cost),
        "unrealised_ltcg":   float(ltcg),
        "unrealised_stcg":   float(stcg),
        "ltcg_taxable":      float(ltcg_taxable),
        "ltcg_tax_estimate": float(ltcg_taxable * LTCG_RATE),
        "stcg_tax_estimate": float(stcg * STCG_RATE),
    }
