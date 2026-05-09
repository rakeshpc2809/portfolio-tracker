-- V3__Add_AA_Tracking_Tables.sql
-- Tracking tables for Sahamati Account Aggregator integration

CREATE TABLE fiu_consent (
    id BIGSERIAL PRIMARY KEY,
    consent_id VARCHAR(50), -- Assigned by AA after approval
    consent_handle VARCHAR(50) UNIQUE, -- Assigned by AA during request
    customer_id VARCHAR(100), -- vpa@aa
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED, EXPIRED
    expiry_date TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fiu_fi_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(50) UNIQUE, -- Assigned by AA
    consent_id VARCHAR(50) REFERENCES fiu_consent(consent_id),
    status VARCHAR(20) DEFAULT 'REQUESTED', -- REQUESTED, READY, COMPLETED, FAILED
    private_key TEXT, -- Session private key for decryption (encrypted or short-lived)
    nonce VARCHAR(50), -- Session nonce
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
