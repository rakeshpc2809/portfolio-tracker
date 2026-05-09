import sys
from datetime import datetime, date
from scipy.optimize import newton
from collections import defaultdict

def xirr(transactions):
    if not transactions: return 0.0
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

scheme_txs = defaultdict(list)
scheme_names = {}

# Current Values (Terminal Flows)
# I'll fetch these from the DB later, but for now I'll just use the ones I saw.

for line in sys.stdin:
    parts = line.split('|')
    if len(parts) < 5: continue
    try:
        s_name = parts[0].strip()
        d_str = parts[1].strip()
        amt = float(parts[2].strip())
        t_type = parts[4].strip().upper()
        
        d = datetime.strptime(d_str, '%Y-%m-%d').date()
        is_outflow = any(x in t_type for x in ["BUY", "PURCHASE", "SWITCH_IN", "STAMP", "STT", "TDS"])
        flow = -abs(amt) if is_outflow else abs(amt)
        
        scheme_txs[s_name].append((d, flow))
        scheme_names[s_name] = s_name
    except:
        continue

# Also add terminal values (approximate from my previous query)
# ... (I'll fetch them precisely now)
