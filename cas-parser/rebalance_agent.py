import numpy as np
import gymnasium as gym
from gymnasium import spaces
from stable_baselines3 import PPO
import logging

logger = logging.getLogger(__name__)

class PortfolioEnv(gym.Env):
    """
    Custom Environment for Portfolio Rebalancing.
    State: [Allocation_1...Allocation_N, HMM_State, Sentiment_Score, Hurst_Exponent]
    """
    def __init__(self, n_assets=5):
        super(PortfolioEnv, self).__init__()
        self.n_assets = n_assets
        # Action space: Discrete adjustments for each asset weight (+0.5%, 0, -0.5%)
        # For simplicity: [Target_Weight_1...Target_Weight_N]
        self.action_space = spaces.Box(low=0, high=1, shape=(n_assets,), dtype=np.float32)
        
        # Observation space: Weights + Macro indicators
        self.observation_space = spaces.Box(low=-1, high=1, shape=(n_assets + 3,), dtype=np.float32)

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        # Random initial state
        state = np.random.uniform(0, 1, size=(self.n_assets + 3,)).astype(np.float32)
        return state, {}

    def step(self, action):
        # Normalize action to sum to 1
        weights = action / np.sum(action)
        
        # Mock Reward calculation: Sharpe Ratio - Friction
        # In production, this would use historical backtest data
        reward = np.random.normal(0.05, 0.02) # Simulated return
        friction = 0.001 # Transaction cost
        total_reward = reward - friction
        
        done = False
        truncated = False
        obs = np.random.uniform(0, 1, size=(self.n_assets + 3,)).astype(np.float32)
        
        return obs, total_reward, done, truncated, {}

def train_agent():
    env = PortfolioEnv(n_assets=5)
    model = PPO("MlpPolicy", env, verbose=1)
    logger.info("Training PPO Rebalance Agent...")
    model.learn(total_timesteps=1000)
    model.save("ppo_rebalance_model")
    return model

def get_ai_rebalance_weights(current_state):
    """
    Inference: Given current state, predict optimal weights.
    """
    try:
        model = PPO.load("ppo_rebalance_model")
        action, _states = model.predict(current_state)
        return action / np.sum(action)
    except Exception as e:
        logger.error(f"DRL Inference failed: {e}")
        return None
