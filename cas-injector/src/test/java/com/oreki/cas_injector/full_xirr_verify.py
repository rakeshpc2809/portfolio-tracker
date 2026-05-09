import sys
from datetime import datetime, date
from scipy.optimize import newton
from collections import defaultdict

def xirr(transactions):
    if not transactions or len(transactions) < 2: return 0.0
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
        lo, hi = -0.99, 10.0
        for _ in range(100):
            mid = (lo + hi) / 2
            if npv(mid) > 0: lo = mid
            else: hi = mid
        return (lo + hi) / 2 * 100

# 1. Read Terminal Values
# Format: SCHEME_NAME|VALUE
terminal_values = {}
with open("/tmp/terminal_values.txt", "r") as f:
    for line in f:
        parts = line.strip().split('|')
        if len(parts) == 2:
            terminal_values[parts[0]] = float(parts[1])

scheme_txs = defaultdict(list)
all_txs = []

# 2. Read Transactions from stdin
for line in sys.stdin:
    parts = line.strip().split('|')
    if len(parts) < 4: continue
    s_name = parts[0].strip()
    d_str = parts[1].strip()
    amt = float(parts[2].strip())
    t_type = parts[3].strip().upper()
    
    d = datetime.strptime(d_str, '%Y-%m-%d').date()
    is_outflow = any(x in t_type for x in ["BUY", "PURCHASE", "SWITCH_IN", "STAMP", "STT", "TDS"])
    flow = -abs(amt) if is_outflow else abs(amt)
    
    scheme_txs[s_name].append((d, flow))
    all_txs.append((d, flow))

today = date(2026, 5, 9)
total_v = 0
for s_name, txs in scheme_txs.items():
    v = terminal_values.get(s_name, 0.0)
    total_v += v
    if v > 0:
        txs.append((today, v))
    res = xirr(txs)
    print(f"{s_name[:40]:40} | XIRR: {res:6.2f}% | Current Value: {v:10.2f}")

all_txs.append((today, total_v))
print("-" * 70)
print(f"{'OVERALL':40} | XIRR: {xirr(all_txs):6.2f}% | Total Value: {total_v:10.2f}")
