# portfolio-dashboard (React Frontend)

The visual interface for Portfolio OS, designed to transform complex quantitative signals into plain English, actionable insights.

## Core Features

- **Unified Dashboard (`PortfolioView`)**: Displays high-level vital signs:
  - **Portfolio CVaR**: Tail risk gauge alerting users if the portfolio is structurally dangerous.
  - **Tax Efficiency**: Percentage of paper profits protected by LTCG.
  - **Regime Pulse**: A visual breakdown of how many funds are currently in Bull vs. Bear states.
  - **Half-life Clock**: The average OU mean reversion speed across the portfolio.
- **Fund Matrix (`FundsListView`)**: A dense, sortable view of all holdings featuring:
  - **Conviction Dots**: A 0-100 visual rating of a fund's structural health.
  - **Price Zone Bar**: A visual slider showing the fund's current price relative to its 3-year historical high/low.
  - **Action Pills**: Clear instructions (BUY, SELL, WATCH, HOLD, EXIT, HARVEST).
  - **Learn Tooltips**: Radix UI-powered floating cards explaining complex financial jargon (like CVaR or Z-Score) in simple terms.
- **Explanation Engine**: Translates raw math (Z-Scores, Hurst exponents) into UI Metaphors ("Rubber Band", "Wave Rider", "Cooling Off") so investors understand *why* the engine is making a recommendation.

## Technology Stack
- React 18
- Vite
- Tailwind CSS
- Radix UI Primitives
- Recharts (for Treemaps and Bar charts)
- Framer Motion
- Lucide React (Icons)
