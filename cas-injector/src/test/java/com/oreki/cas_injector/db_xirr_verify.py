import sys
from datetime import datetime, date
from scipy.optimize import newton

def xirr(transactions):
    if not transactions:
        return 0.0
    transactions.sort(key=lambda x: x[0])
    d0 = transactions[0][0]
    def npv(rate):
        total = 0.0
        for d, amt in transactions:
            days = (d - d0).days
            total += amt / (1 + rate) ** (days / 365.25)
        return total
    try:
        return newton(npv, 0.1) * 100
    except:
        # Bisection fallback
        lo, hi = -0.99, 10.0
        for _ in range(100):
            mid = (lo + hi) / 2
            if npv(mid) > 0: lo = mid
            else: hi = mid
        return (lo + hi) / 2 * 100

txs = []
# Terminal Value
txs.append((date(2026, 5, 9), 1641127.23))

# Read from stdin (output of psql)
# Expected format: YYYY-MM-DD | amount | units | type
for line in sys.stdin:
    parts = line.split('|')
    if len(parts) < 4: continue
    try:
        d_str = parts[0].strip()
        amt = float(parts[2].strip())
        t_type = parts[3].strip().upper()
        
        d = datetime.strptime(d_str, '%Y-%m-%d').date()
        
        is_outflow = any(x in t_type for x in ["BUY", "PURCHASE", "SWITCH_IN", "STAMP", "STT", "TDS"])
        flow = -abs(amt) if is_outflow else abs(amt)
        
        txs.append((d, flow))
    except:
        continue

print(f"Total Transactions: {len(txs)}")
res = xirr(txs)
print(f"XIRR: {res}%")
