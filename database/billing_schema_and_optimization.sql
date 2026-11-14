-- ============================================================================
-- STREAMING BILLING & SUBSCRIPTION SCHEMA WITH PERFORMANCE OPTIMIZATION
-- ============================================================================

CREATE TABLE subscriptions (
    subscription_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(64) NOT NULL UNIQUE,
    plan_tier VARCHAR(30) NOT NULL CHECK (plan_tier IN ('BASIC', 'STANDARD', 'PREMIUM_4K')),
    monthly_rate DECIMAL(10, 2) NOT NULL CHECK (monthly_rate >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    next_billing_date DATE NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE billing_transactions (
    transaction_id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    account_id VARCHAR(64) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) DEFAULT 'USD',
    payment_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sub_account FOREIGN KEY (account_id) REFERENCES subscriptions(account_id)
);

-- Query Optimization: Covering index for high-frequency invoice lookups & reconciliation
CREATE INDEX idx_tx_account_status_date 
ON billing_transactions (account_id, payment_status, created_at DESC);

-- Analytical Query: Monthly Recurring Revenue (MRR) & Rolling Revenue Rank by Plan Tier
WITH MonthlyRevenue AS (
    SELECT 
        s.plan_tier,
        DATE_FORMAT(t.created_at, '%Y-%m-01') AS billing_month,
        COUNT(DISTINCT t.account_id) AS billed_accounts,
        SUM(t.amount) AS total_mrr
    FROM billing_transactions t
    INNER JOIN subscriptions s ON t.account_id = s.account_id
    WHERE t.payment_status = 'CAPTURED'
    GROUP BY s.plan_tier, DATE_FORMAT(t.created_at, '%Y-%m-01')
)
SELECT 
    plan_tier,
    billing_month,
    billed_accounts,
    total_mrr,
    ROUND(
        total_mrr - LAG(total_mrr) OVER (PARTITION BY plan_tier ORDER BY billing_month), 
        2
    ) AS mrr_mom_growth
FROM MonthlyRevenue
ORDER BY billing_month DESC, total_mrr DESC;
