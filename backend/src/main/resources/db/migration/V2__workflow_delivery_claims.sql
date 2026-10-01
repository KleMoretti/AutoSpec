-- AutoSpec P2-P4 append-only delivery and recovery state.
-- V1 is the immutable local baseline; this migration is intentionally additive.

ALTER TABLE workflow_outbox ADD COLUMN claim_owner varchar(128) DEFAULT NULL;
ALTER TABLE workflow_outbox ADD COLUMN claim_until timestamp DEFAULT NULL;
ALTER TABLE workflow_outbox ADD COLUMN claim_version int NOT NULL DEFAULT 0;

CREATE INDEX idx_workflow_outbox_claim
    ON workflow_outbox (status, claim_until, id);

ALTER TABLE artifact
    ADD COLUMN upload_idempotency_key varchar(128) DEFAULT NULL;

CREATE UNIQUE INDEX uk_artifact_project_upload_idempotency
    ON artifact (project_id, type, upload_idempotency_key);

ALTER TABLE project_memory_fact
    ADD COLUMN trust_status varchar(32) NOT NULL DEFAULT 'UNTRUSTED';

CREATE INDEX idx_project_memory_trusted_recall
    ON project_memory_fact (project_id, trust_status, conflict_status, fact_type, valid_until_at, expires_at);

CREATE TABLE workflow_event_delivery_attempt (
    stream_message_id varchar(128) NOT NULL,
    consumer_group varchar(128) NOT NULL,
    delivery_count int NOT NULL DEFAULT 0,
    last_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stream_message_id, consumer_group)
);

CREATE INDEX idx_workflow_event_delivery_attempt_count
    ON workflow_event_delivery_attempt (consumer_group, delivery_count, last_attempt_at);
