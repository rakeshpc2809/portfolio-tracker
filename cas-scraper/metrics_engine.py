import os
import time
import json
import requests
from datetime import date
from sqlalchemy import create_engine, Column, Integer, String, Float, Date, UniqueConstraint, text
from sqlalchemy.orm import declarative_base, sessionmaker
from sqlalchemy.dialects.postgresql import insert

# Configuration
DB_URL = os.getenv('DATABASE_URL', 'postgresql://user:password@postgres:5432/cas_db')
spring_db_url = os.getenv('SPRING_DATASOURCE_URL', '')
if spring_db_url.startswith('jdbc:postgresql://'):
    parts = spring_db_url.split('jdbc:postgresql://')[1].split('?')[0]
    username = os.getenv('SPRING_DATASOURCE_USERNAME', 'user')
    password = os.getenv('SPRING_DATASOURCE_PASSWORD', 'password')
    DB_URL = f"postgresql://{username}:{password}@{parts}"

MFDATA = 'https://mfdata.in/api/v1'
MFAPI  = 'https://api.mfapi.in/mf'

# Database Setup
engine = create_engine(DB_URL)
Base = declarative_base()

class FundMetric(Base):
    __tablename__ = 'fund_metrics'
    id = Column(Integer, primary_key=True)
    fetch_date = Column(Date, nullable=False)
    scheme_code = Column(String(50), nullable=False)
    scheme_name = Column(String(255))
    nav = Column(Float)
    expense_ratio = Column(Float)
    aum_cr = Column(Float)
    __table_args__ = (UniqueConstraint('fetch_date', 'scheme_code', name='uq_fetch_date_scheme_code'),)

Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

def get_watchlist(engine):
    try:
        with engine.connect() as conn:
            query = text("SELECT DISTINCT amfi_code, name FROM scheme WHERE amfi_code IS NOT NULL")
            result = conn.execute(query)
            return {row[0]: row[1] for row in result}
    except: return {}

def daily_fetch():
    session = Session()
    today = date.today()
    watchlist = get_watchlist(engine)
    
    print(f"\n{'='*60}")
    print(f"MF Metrics Engine v3.0 (NAV Focused) - Starting run for {today}")
    print(f"{'='*60}")

    for code, name in watchlist.items():
        print(f"\n-> {name} ({code})")
        try:
            # Fetch NAV and basic metrics from API
            r_detail = requests.get(f'{MFDATA}/schemes/{code}', timeout=10)
            d_res = r_detail.json()
            
            nav, expense, aum = None, None, None

            if d_res.get('status') == 'success':
                d = d_res.get('data', {})
                nav = d.get('nav')
                expense = d.get('ratios', {}).get('expense_ratio', {}).get('value')
                aum = d.get('aum', {}).get('value')
                print(f"   Fetched: NAV={nav}, Expense={expense}, AUM={aum}")

            # Upsert
            stmt = insert(FundMetric).values(
                fetch_date=today, scheme_code=code, scheme_name=name,
                nav=nav, expense_ratio=expense, aum_cr=aum
            )
            on_conflict = stmt.on_conflict_do_update(
                constraint='uq_fetch_date_scheme_code',
                set_={'nav': stmt.excluded.nav, 'expense_ratio': stmt.excluded.expense_ratio,
                      'aum_cr': stmt.excluded.aum_cr}
            )
            session.execute(on_conflict)
            session.commit()
        except Exception as e:
            session.rollback()
            print(f"   ERROR: {e}")

    session.close()
    print(f"\n{'='*60}\nDone. Metrics sync complete.\n{'='*60}")

if __name__ == "__main__":
    daily_fetch()
