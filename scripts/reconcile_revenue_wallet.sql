-- ============================================================
-- Reconcile revenue wallet (user_id 2) to match Rubies balance
-- Gap: DB ₦8,165.11 vs Rubies ₦5,947.74 = ₦2,217.37
-- Cause: Rubies KYC/BVN fees (₦18→₦70) deducted at BaaS level,
--        never recorded in our DB.
-- ============================================================

BEGIN;

-- ── 1. Verify current state ──
DO $$
DECLARE
    v_current_balance NUMERIC;
    v_rubies_balance  NUMERIC := 5947.74;
    v_gap             NUMERIC;
BEGIN
    SELECT balance INTO v_current_balance
      FROM wallets
     WHERE user_id = 2 AND is_revenue_wallet = TRUE
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Revenue wallet not found for user_id 2';
    END IF;

    v_gap := v_current_balance - v_rubies_balance;

    RAISE NOTICE 'Revenue wallet current balance: ₦%', v_current_balance;
    RAISE NOTICE 'Rubies actual balance:          ₦%', v_rubies_balance;
    RAISE NOTICE 'Gap (unrecorded BaaS KYC fees): ₦%', v_gap;

    IF v_gap <= 0 THEN
        RAISE EXCEPTION 'No positive gap — DB balance (%) is not above Rubies balance (%). Aborting.',
            v_current_balance, v_rubies_balance;
    END IF;
END $$;

-- ── 2. Record the adjustment as a transaction log for audit trail ──
INSERT INTO transaction_logs (
    user_id, amount, transaction_type, status,
    created_at, reference, description
) VALUES (
    2,
    (SELECT balance - 5947.74 FROM wallets WHERE user_id = 2 AND is_revenue_wallet = TRUE),
    'WALLET_DEDUCTION',
    'COMPLETED',
    NOW(),
    'RECON-KYC-FEE-' || TO_CHAR(NOW(), 'YYYYMMDD-HH24MISS'),
    'Reconciliation: unrecorded Rubies KYC/BVN verification fees (₦18→₦70 price change unnotified). Gap discovered 2026-10-08.'
);

-- ── 3. Correct the balance ──
UPDATE wallets
   SET balance = 5947.74
 WHERE user_id = 2 AND is_revenue_wallet = TRUE;

-- ── 4. Verify ──
SELECT 'REVENUE WALLET' AS entity,
       balance::TEXT AS new_balance,
       '5947.74' AS expected
  FROM wallets
 WHERE user_id = 2 AND is_revenue_wallet = TRUE;

COMMIT;
