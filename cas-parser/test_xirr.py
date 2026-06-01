import datetime
from quant_batch import calculate_xirr

def test_calculate_xirr_empty():
    assert calculate_xirr([], 10.0) == 0.0

def test_calculate_xirr_single():
    tx = [{"total_amount": 1000, "units": 100, "transaction_type": "BUY", "transaction_date": datetime.date(2026, 1, 1)}]
    # Single cash flow shouldn't calculate XIRR
    assert calculate_xirr(tx, 10.0) == 0.0

def test_calculate_xirr_growth():
    # Buy 100 units for 1000 total (NAV = 10) on Jan 1
    # On Dec 31, NAV is 12 (Current value = 1200)
    tx = [{"total_amount": 1000, "units": 100, "transaction_type": "BUY", "transaction_date": datetime.date(2026, 1, 1)}]
    xirr_val = calculate_xirr(tx, 12.0)
    # Expected XIRR is close to 20%
    assert abs(xirr_val - 20.0) < 1.0
