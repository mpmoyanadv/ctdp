CREATE UNIQUE INDEX uq_disputes_active_transaction
    ON disputes (transaction_id)
    WHERE transaction_id IS NOT NULL
      AND status NOT IN ('RESOLVED', 'REFUNDED', 'REJECTED');

