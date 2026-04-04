# Design Document: portfolio-dashboard

The `portfolio-dashboard` is a modern, high-performance web interface designed to provide an "architectural" view of investment data.

## Responsibilities

1.  **Data Visualization**: Displays complex portfolio metrics, scheme-level performance, and market conviction scores in an intuitive way.
2.  **Portfolio Syncing**: Communicates with the `cas-injector` backend to retrieve the latest portfolio state for a specific investor.
3.  **User Experience**: Provides a high-contrast, immersive, and responsive interface with smooth transitions and real-time feedback.

## Architectural Approach

### 1. "Architectural" UI Design
The dashboard uses a dark-themed, futuristic aesthetic (often called "Cyberpunk" or "Sci-Fi" UI) to represent high-tech financial analysis. This is achieved through:
-   **High Contrast**: Deep blacks (`#020202`) with vibrant accents (Blue, Rose, Zinc).
-   **Motion**: Extensive use of `framer-motion` for page transitions, entry animations, and interactive elements.
-   **Data Density**: Prioritizing clarity and information density over traditional whitespace.

### 2. State Management & Data Fetching
-   **React Hooks**: Uses `useState` and `useEffect` for local state and side effects.
-   **Centralized API Client**: A dedicated `api.ts` service layer for all backend communications, ensuring consistent error handling and endpoint management.

### 3. Component Hierarchy
-   **`App.tsx`**: The main entry point, handling global loading states and architectural "uplink" simulations.
-   **`Dashboard.tsx`**: The main layout container.
-   **Modular Components**: Smaller, reusable components for charts, data cards, and navigation (located in `src/components`).

## Technology Stack

-   **Framework**: React 18
-   **Language**: TypeScript
-   **Styling**: CSS (Modern Vanilla/Standard CSS)
-   **Animations**: Framer Motion
-   **Icons**: SVG-based custom icon system
-   **Build Tool**: Vite

## Key Features

-   **Syncing Loader**: A custom-built, animated loader that simulates a "secure uplink" to the backend, reinforcing the system's high-tech theme.
-   **AnimatePresence**: Used to manage entry and exit animations of major UI sections.
-   **Responsive Layout**: Designed to work across different screen sizes while maintaining its dense, data-focused look.
