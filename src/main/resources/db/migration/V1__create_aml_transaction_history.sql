CREATE TABLE IF NOT EXISTS aml_transaction_history (
    transaction_id VARCHAR(100) PRIMARY KEY,
    customer_id VARCHAR(100) NOT NULL,
    account_number VARCHAR(100) NOT NULL,
    amount NUMERIC(20, 4) NOT NULL,
    currency VARCHAR(10),
    direction VARCHAR(20) NOT NULL,
    transaction_date TIMESTAMP NOT NULL,
    counterparty_name VARCHAR(255),
    counterparty_account VARCHAR(255),
    transaction_purpose TEXT,
    risk_score NUMERIC(10, 6),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aml_history_account_date
    ON aml_transaction_history (account_number, transaction_date DESC);

CREATE INDEX IF NOT EXISTS idx_aml_history_account_direction_date
    ON aml_transaction_history (account_number, direction, transaction_date DESC);
