-- Preserve candidate and trusted verifier references in the durable Replan Trace.
-- V1 and the existing V2-V4 migrations are immutable; this migration is additive.

ALTER TABLE workflow_agent_step_fact
    ADD COLUMN candidate_hash varchar(64) DEFAULT NULL;

ALTER TABLE workflow_agent_step_fact
    ADD COLUMN verification_fact_ref varchar(64) DEFAULT NULL;
