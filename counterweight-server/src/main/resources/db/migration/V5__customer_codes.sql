-- ═══════════════════════════════════════════════════════════════════════════
--  V5 — customer codes and receivables lookup
-- ═══════════════════════════════════════════════════════════════════════════

-- A plain PostgreSQL SEQUENCE, deliberately — and deliberately NOT the
-- `document_sequence` table.
--
-- §8.3 rejects sequences for document numbers because they leak values on
-- rollback, and a receipt register with holes in it is one an auditor has to
-- ask about. None of that applies to a customer code: it identifies an account,
-- it appears on no statutory document, and nobody reconciles the set of them.
-- What it does need is to stay unique when two clerks add a customer at the
-- same counter at the same moment, which is exactly what a sequence gives for
-- free and what a MAX(code) + 1 read does not.
--
-- Starting at 1000 so codes are a stable four digits from the first customer
-- rather than widening from CUS-000001; the pad below still covers growth.

CREATE SEQUENCE customer_code_seq START WITH 1000 INCREMENT BY 1;

-- Ageing resolves each invoice against its allocations, which reads
-- payment_allocation by invoice. The table's unique constraint indexes
-- (payment_entry_id, invoice_entry_id) and so serves the other direction only.
CREATE INDEX idx_payment_allocation_invoice ON payment_allocation (invoice_entry_id);

-- Receivables are always read per customer and mostly for open invoices. The
-- V1 index on (customer_id, occurred_at DESC) serves the statement view; this
-- one serves the collection view.
CREATE INDEX idx_customer_ledger_invoices
    ON customer_ledger_entry (customer_id, due_on)
    WHERE entry_type IN ('INVOICE', 'OPENING_BALANCE');
