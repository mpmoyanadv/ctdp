ALTER TABLE disputes
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE disputes
    ADD CONSTRAINT chk_disputes_amount_positive CHECK (amount > 0);

CREATE SEQUENCE dispute_ref_seq START WITH 10100 INCREMENT BY 1;
