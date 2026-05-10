import numpy as np

def monte_carlo_projection(daily_returns: list[float], current_value: float,
                           monthly_sip: float, horizon_months: int,
                           n_simulations: int = 1000) -> dict:
    results = []
    if not daily_returns:
        return {"p10": current_value, "p50": current_value, "p90": current_value}
        
    returns_arr = np.array(daily_returns)
    for _ in range(n_simulations):
        val = current_value
        for m in range(horizon_months):
            # sample 21 trading days
            monthly_r = np.prod(1 + np.random.choice(returns_arr, 21)) - 1
            val = val * (1 + monthly_r) + monthly_sip
        results.append(val)
    results.sort()
    return {
        "p10": results[int(0.10 * n_simulations)],
        "p50": results[int(0.50 * n_simulations)],
        "p90": results[int(0.90 * n_simulations)],
    }
