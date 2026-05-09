from datetime import date
from scipy.optimize import newton

def xirr(transactions):
    """
    Calculate the Internal Rate of Return for a series of cash flows.
    transactions is a list of (date, amount) tuples.
    """
    if not transactions:
        return 0.0
    
    # Sort by date
    transactions.sort(key=lambda x: x[0])
    d0 = transactions[0][0]
    
    def npv(rate):
        total = 0.0
        for d, amt in transactions:
            days = (d - d0).days
            total += amt / (1 + rate) ** (days / 365.25)
        return total

    try:
        # Use a reasonable guess of 10%
        return newton(npv, 0.1) * 100
    except:
        return None

# Transactions data (manually extracted a few to check the trend)
# Inflow: Current Value = 1641127.23 on 2026-05-09
# Outflows: All BUYS/PURCHASES as negative
# Inflows: All SELLS/SWITCH_OUTS as positive

txs = [
    (date(2026, 5, 9), 1641127.23), # Terminal Value
]

# I will parse the output from the DB to populate this.
# For now, I'll just look at the last few months of transactions to see if they make sense.
