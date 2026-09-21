CREATE TABLE project_memory_fact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    fact_key VARCHAR(255) NOT NULL,
    value_json LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    version INT NOT NULL,
    conflict_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    source_workflow_run_id BIGINT NULL,
    source_node_run_id BIGINT NULL,
    source_artifact_id BIGINT NULL,
    source_artifact_type VARCHAR(64) NULL,
    source_artifact_version INT NULL,
    valid_from_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_memory_project
        FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_memory_workflow_run
        FOREIGN KEY (source_workflow_run_id) REFERENCES workflow_run(id),
    CONSTRAINT fk_project_memory_node_run
        FOREIGN KEY (source_node_run_id) REFERENCES workflow_node_run(id),
    CONSTRAINT fk_project_memory_artifact
        FOREIGN KEY (source_artifact_id) REFERENCES artifact(id),
    CONSTRAINT uk_project_memory_fact_version
        UNIQUE (project_id, fact_type, fact_key, version)
);

CREATE INDEX idx_project_memory_recall
    ON project_memory_fact(project_id, conflict_status, fact_type, valid_until_at, expires_at);
CREATE INDEX idx_project_memory_source_artifact
    ON project_memory_fact(source_artifact_id, fact_type, fact_key);
