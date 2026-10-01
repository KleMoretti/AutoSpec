-- AutoSpec database baseline. Subsequent schema changes start at V2.
-- Historical runtime identities are independent of the database migration number.

CREATE TABLE `artifact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `type` varchar(64) NOT NULL,
  `title` varchar(256) NOT NULL,
  `content` longtext NOT NULL,
  `format` varchar(32) NOT NULL,
  `version` int NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `status` varchar(32) NOT NULL DEFAULT 'GENERATED',
  `source_agent` varchar(128) DEFAULT NULL,
  `parent_artifact_id` bigint DEFAULT NULL,
  `approved_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `workflow_node_run_id` bigint DEFAULT NULL,
  `lock_version` int NOT NULL DEFAULT '0',
  `content_hash` varchar(64) DEFAULT NULL,
  `schema_version` varchar(32) NOT NULL DEFAULT 'v1',
  `prompt_key` varchar(128) DEFAULT NULL,
  `prompt_version` varchar(64) DEFAULT NULL,
  `model_provider` varchar(64) DEFAULT NULL,
  `model_name` varchar(128) DEFAULT NULL,
  `source_citations_json` longtext,
  `provenance_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artifact_project_type_version` (`project_id`,`type`,`version`),
  KEY `idx_artifact_project_id` (`project_id`),
  KEY `idx_artifact_type` (`type`),
  KEY `idx_artifact_status` (`status`),
  KEY `idx_artifact_project_id_id` (`project_id`,`id`),
  KEY `idx_artifact_project_type_version` (`project_id`,`type`,`version`),
  KEY `idx_artifact_project_type_id` (`project_id`,`type`,`id`),
  KEY `idx_artifact_workflow_node_run_id` (`workflow_node_run_id`),
  KEY `idx_artifact_project_content_hash` (`project_id`,`content_hash`)
);

CREATE TABLE `artifact_component` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `workflow_run_id` bigint DEFAULT NULL,
  `artifact_id` bigint NOT NULL,
  `artifact_type` varchar(64) NOT NULL,
  `component_key` varchar(128) NOT NULL,
  `component_type` varchar(64) NOT NULL,
  `display_name` varchar(512) NOT NULL,
  `json_path` varchar(512) NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artifact_component_key` (`artifact_id`,`component_key`),
  KEY `idx_artifact_component_project_run` (`project_id`,`workflow_run_id`,`component_type`),
  KEY `idx_artifact_component_key` (`project_id`,`component_key`)
);

CREATE TABLE `artifact_trace_edge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `workflow_run_id` bigint DEFAULT NULL,
  `source_artifact_id` bigint NOT NULL,
  `from_component_key` varchar(128) NOT NULL,
  `to_component_key` varchar(128) NOT NULL,
  `relation_type` varchar(64) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artifact_trace_edge` (`source_artifact_id`,`from_component_key`,`to_component_key`,`relation_type`),
  KEY `idx_artifact_trace_project_run` (`project_id`,`workflow_run_id`,`relation_type`),
  KEY `idx_artifact_trace_target` (`project_id`,`to_component_key`)
);

CREATE TABLE `audit_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `actor_user_id` bigint DEFAULT NULL,
  `event_type` varchar(64) NOT NULL,
  `entity_type` varchar(64) NOT NULL,
  `entity_id` bigint DEFAULT NULL,
  `message` varchar(512) NOT NULL,
  `metadata` longtext,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `correlation_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_audit_event_actor_user` (`actor_user_id`),
  KEY `idx_audit_event_project_id` (`project_id`),
  KEY `idx_audit_event_type` (`event_type`),
  KEY `idx_audit_event_entity` (`entity_type`,`entity_id`),
  KEY `idx_audit_event_project_id_id` (`project_id`,`id`),
  KEY `idx_audit_event_project_correlation_id` (`project_id`,`correlation_id`)
);

CREATE TABLE `code_generation_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `manifest` longtext,
  `error_message` text,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` timestamp NULL DEFAULT NULL,
  `cancelled_at` timestamp NULL DEFAULT NULL,
  `retry_of_job_id` bigint DEFAULT NULL,
  `gate_status` varchar(32) DEFAULT NULL,
  `manifest_hash` varchar(64) DEFAULT NULL,
  `verification_json` longtext,
  `verified_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_code_generation_job_project_id` (`project_id`),
  KEY `idx_code_generation_job_retry_of_job_id` (`retry_of_job_id`),
  KEY `idx_code_generation_job_project_id_id` (`project_id`,`id`),
  KEY `idx_code_generation_job_status_created_at` (`status`,`created_at`),
  KEY `idx_code_generation_job_project_status_id` (`project_id`,`status`,`id`),
  KEY `idx_code_generation_delivery_gate` (`project_id`,`gate_status`,`id`)
);

CREATE TABLE `export_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `job_id` bigint DEFAULT NULL,
  `file_name` varchar(256) NOT NULL,
  `media_type` varchar(128) NOT NULL,
  `encoding` varchar(32) NOT NULL,
  `content` longtext NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_export_file_project_id` (`project_id`),
  KEY `idx_export_file_project_id_id` (`project_id`,`id`)
);

CREATE TABLE `knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `chunk_index` int NOT NULL,
  `content` text NOT NULL,
  `token_hint` int NOT NULL DEFAULT '0',
  `retrieval_terms` varchar(512) NOT NULL,
  `vector_ref` varchar(256) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `content_hash` varchar(64) DEFAULT NULL,
  `embedding_model` varchar(128) DEFAULT NULL,
  `embedding_dimensions` int DEFAULT NULL,
  `embedding_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chunk` (`document_id`,`chunk_index`),
  KEY `idx_knowledge_chunk_terms` (`retrieval_terms`),
  KEY `idx_knowledge_chunk_content_hash` (`content_hash`)
);

CREATE TABLE `knowledge_corpus_epoch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `corpus_epoch` bigint NOT NULL DEFAULT '1',
  `access_policy_version` varchar(128) NOT NULL,
  `last_invalidation_reason` varchar(128) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_corpus_epoch_project` (`project_id`),
  KEY `idx_knowledge_corpus_epoch_updated` (`updated_at`)
);

CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `artifact_id` bigint NOT NULL,
  `artifact_type` varchar(64) NOT NULL,
  `artifact_version` int NOT NULL,
  `title` varchar(256) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'INDEXED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `content_hash` varchar(64) DEFAULT NULL,
  `chunker_version` varchar(64) DEFAULT NULL,
  `embedding_model` varchar(128) DEFAULT NULL,
  `failure_message` varchar(1024) DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `activated_at` timestamp NULL DEFAULT NULL,
  `superseded_at` timestamp NULL DEFAULT NULL,
  `corpus_type` varchar(32) NOT NULL DEFAULT 'PROJECT_ARTIFACT',
  `expires_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_artifact` (`artifact_id`),
  KEY `idx_knowledge_document_project_id` (`project_id`),
  KEY `idx_knowledge_document_active_version` (`project_id`,`artifact_type`,`status`,`artifact_version`),
  KEY `idx_knowledge_document_corpus_status_version` (`project_id`,`corpus_type`,`status`,`artifact_version`),
  KEY `idx_knowledge_document_expiry_status` (`expires_at`,`status`)
);

CREATE TABLE `model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_key` varchar(64) NOT NULL,
  `model_name` varchar(128) NOT NULL,
  `agent_node` varchar(128) NOT NULL,
  `priority` int NOT NULL DEFAULT '100',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_config` (`provider_key`,`model_name`,`agent_node`),
  KEY `idx_model_config_node` (`agent_node`,`enabled`,`priority`)
);

CREATE TABLE `model_invocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `task_id` bigint DEFAULT NULL,
  `provider_key` varchar(64) NOT NULL,
  `model_name` varchar(128) NOT NULL,
  `agent_node` varchar(128) NOT NULL,
  `prompt_version_id` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `duration_ms` int NOT NULL DEFAULT '0',
  `input_tokens` int NOT NULL DEFAULT '0',
  `output_tokens` int NOT NULL DEFAULT '0',
  `estimated_cost` decimal(12,6) DEFAULT NULL,
  `score` decimal(5,2) DEFAULT NULL,
  `error_message` text,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `workflow_run_id` bigint DEFAULT NULL,
  `correlation_id` varchar(64) DEFAULT NULL,
  `workflow_node_run_id` bigint DEFAULT NULL,
  `cache_tokens` int NOT NULL DEFAULT '0',
  `prompt_key` varchar(128) DEFAULT NULL,
  `call_count` int NOT NULL DEFAULT '1',
  `route_key` varchar(64) DEFAULT NULL,
  `route_reason` varchar(512) DEFAULT NULL,
  `fallback_used` tinyint(1) NOT NULL DEFAULT '0',
  `context_manifest_json` longtext,
  `prompt_version` varchar(64) DEFAULT NULL,
  `prompt_checksum` varchar(64) DEFAULT NULL,
  `contract_hash` varchar(64) DEFAULT NULL,
  `call_id` varchar(255) DEFAULT NULL,
  `call_type` varchar(16) NOT NULL DEFAULT 'MODEL',
  `execution_id` varchar(255) DEFAULT NULL,
  `call_sequence` int DEFAULT NULL,
  `attempt` int DEFAULT NULL,
  `schema_version` varchar(128) DEFAULT NULL,
  `reserved_input_tokens` int NOT NULL DEFAULT '0',
  `reserved_output_tokens` int NOT NULL DEFAULT '0',
  `reserved_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `settlement_delta_tokens` int NOT NULL DEFAULT '0',
  `settlement_delta_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `normalized_params_hash` varchar(64) DEFAULT NULL,
  `result_hash` varchar(64) DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `deadline_epoch_ms` bigint DEFAULT NULL,
  `idempotency_key` varchar(255) DEFAULT NULL,
  `tool_name` varchar(128) DEFAULT NULL,
  `tool_version` varchar(64) DEFAULT NULL,
  `permission_policy` varchar(128) DEFAULT NULL,
  `reference_sources_json` longtext,
  `redacted_params_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_invocation_call_id` (`call_id`),
  KEY `idx_model_invocation_project_id` (`project_id`),
  KEY `idx_model_invocation_agent_node` (`agent_node`),
  KEY `idx_model_invocation_project_id_id` (`project_id`,`id`),
  KEY `idx_model_invocation_workflow_run_id` (`workflow_run_id`),
  KEY `idx_model_invocation_project_correlation_id` (`project_id`,`correlation_id`),
  KEY `idx_model_invocation_project_status_id` (`project_id`,`status`,`id`),
  KEY `idx_model_invocation_workflow_node_run_id` (`workflow_node_run_id`),
  KEY `idx_model_invocation_execution_sequence` (`execution_id`,`call_sequence`),
  KEY `idx_model_invocation_call_type_status` (`call_type`,`status`,`id`)
);

CREATE TABLE `model_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_key` varchar(64) NOT NULL,
  `display_name` varchar(128) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_key` (`provider_key`)
);

CREATE TABLE `processed_workflow_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(128) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `processed_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_processed_workflow_event_id` (`event_id`)
);

CREATE TABLE `project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `original_requirement` longtext NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'CREATED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project_user_id` (`user_id`),
  KEY `idx_project_user_id_id` (`user_id`,`id`)
);

CREATE TABLE `project_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role` varchar(32) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`,`user_id`),
  KEY `idx_project_member_project_id` (`project_id`),
  KEY `idx_project_member_user_id` (`user_id`),
  KEY `idx_project_member_user_id_project_id` (`user_id`,`project_id`)
);

CREATE TABLE `project_memory_fact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `fact_type` varchar(32) NOT NULL,
  `fact_key` varchar(255) NOT NULL,
  `value_json` longtext NOT NULL,
  `content_hash` char(64) NOT NULL,
  `version` int NOT NULL,
  `conflict_status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `source_type` varchar(32) NOT NULL,
  `source_ref` varchar(255) NOT NULL,
  `source_workflow_run_id` bigint DEFAULT NULL,
  `source_node_run_id` bigint DEFAULT NULL,
  `source_artifact_id` bigint DEFAULT NULL,
  `source_artifact_type` varchar(64) DEFAULT NULL,
  `source_artifact_version` int DEFAULT NULL,
  `valid_from_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `valid_until_at` timestamp NULL DEFAULT NULL,
  `expires_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_memory_fact_version` (`project_id`,`fact_type`,`fact_key`,`version`),
  KEY `fk_project_memory_workflow_run` (`source_workflow_run_id`),
  KEY `fk_project_memory_node_run` (`source_node_run_id`),
  KEY `idx_project_memory_recall` (`project_id`,`conflict_status`,`fact_type`,`valid_until_at`,`expires_at`),
  KEY `idx_project_memory_source_artifact` (`source_artifact_id`,`fact_type`,`fact_key`)
);

CREATE TABLE `prompt_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt_key` varchar(128) NOT NULL,
  `version` varchar(32) NOT NULL,
  `content` longtext NOT NULL,
  `checksum` varchar(128) NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_version` (`prompt_key`,`version`),
  KEY `idx_prompt_version_active` (`prompt_key`,`active`)
);

CREATE TABLE `review_issue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `severity` varchar(32) NOT NULL,
  `issue_type` varchar(64) NOT NULL,
  `description` text NOT NULL,
  `suggestion` text,
  `status` varchar(32) NOT NULL DEFAULT 'OPEN',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `issue_key` varchar(128) DEFAULT NULL,
  `artifact_type` varchar(64) DEFAULT NULL,
  `artifact_path` varchar(256) DEFAULT NULL,
  `requirement_id` varchar(128) DEFAULT NULL,
  `evidence` text,
  `owner_user_id` bigint DEFAULT NULL,
  `resolution` text,
  `resolved_in_artifact_id` bigint DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_review_issue_project_id` (`project_id`),
  KEY `idx_review_issue_status` (`status`),
  KEY `idx_review_issue_project_id_id` (`project_id`,`id`),
  KEY `idx_review_issue_project_status_severity_id` (`project_id`,`status`,`severity`,`id`),
  KEY `idx_review_issue_project_key` (`project_id`,`issue_key`)
);

CREATE TABLE `user_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(128) NOT NULL,
  `display_name` varchar(128) NOT NULL,
  `password_hash` varchar(256) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `platform_role` varchar(32) NOT NULL DEFAULT 'PROJECT_USER',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_username` (`username`)
);

CREATE TABLE `user_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `token_hash` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `expires_at` timestamp NOT NULL,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_session_token_hash` (`token_hash`),
  KEY `idx_user_session_user_active` (`user_id`,`revoked_at`,`expires_at`),
  KEY `idx_user_session_expiry` (`expires_at`)
);

CREATE TABLE `workflow_agent_step_fact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_run_id` bigint NOT NULL,
  `node_run_id` bigint NOT NULL,
  `node_id` varchar(128) NOT NULL,
  `revision` int NOT NULL,
  `attempt` int NOT NULL,
  `execution_id` varchar(255) NOT NULL,
  `contract_hash` varchar(64) DEFAULT NULL,
  `step` int NOT NULL,
  `phase` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `reason_code` varchar(128) DEFAULT NULL,
  `plan_hash` varchar(64) DEFAULT NULL,
  `observation_hash` varchar(64) DEFAULT NULL,
  `validation_issue_codes_json` longtext,
  `model_call_ref` varchar(255) DEFAULT NULL,
  `tool_call_ref` varchar(255) DEFAULT NULL,
  `started_at_epoch_ms` bigint DEFAULT NULL,
  `finished_at_epoch_ms` bigint DEFAULT NULL,
  `duration_ms` int DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_agent_step_execution` (`execution_id`,`step`),
  KEY `idx_workflow_agent_step_run` (`workflow_run_id`,`node_run_id`,`step`),
  KEY `idx_workflow_agent_step_node` (`node_id`,`created_at`)
);

CREATE TABLE `workflow_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_run_id` bigint NOT NULL,
  `node_run_id` bigint NOT NULL,
  `mode` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `decision` varchar(32) DEFAULT NULL,
  `candidate_artifact_id` bigint DEFAULT NULL,
  `revised_artifact_id` bigint DEFAULT NULL,
  `decided_by_user_id` bigint DEFAULT NULL,
  `decision_reason` text,
  `idempotency_key` varchar(128) NOT NULL,
  `decided_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lock_version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_approval_idempotency` (`workflow_run_id`,`idempotency_key`),
  KEY `fk_workflow_approval_node_run` (`node_run_id`),
  KEY `idx_workflow_approval_run_status` (`workflow_run_id`,`status`)
);

CREATE TABLE `workflow_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_key` varchar(128) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` text,
  `status` varchar(32) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_definition_key` (`workflow_key`)
);

CREATE TABLE `workflow_event_dead_letter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stream_message_id` varchar(128) NOT NULL,
  `payload_json` longtext NOT NULL,
  `error_type` varchar(128) NOT NULL,
  `error_message` text,
  `status` varchar(32) NOT NULL DEFAULT 'OPEN',
  `replayed_at` timestamp NULL DEFAULT NULL,
  `closed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_event_dead_letter_message` (`stream_message_id`),
  KEY `idx_workflow_event_dead_letter_status_id` (`status`,`id`)
);

CREATE TABLE `workflow_execution_bundle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_version_id` bigint NOT NULL,
  `bundle_version` varchar(32) NOT NULL,
  `bundle_json` longtext NOT NULL,
  `bundle_hash` varchar(64) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_execution_bundle_version` (`workflow_version_id`,`bundle_version`),
  UNIQUE KEY `uk_workflow_execution_bundle_hash` (`bundle_hash`),
  KEY `idx_workflow_execution_bundle_version` (`workflow_version_id`,`id`)
);

CREATE TABLE `workflow_node_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_run_id` bigint NOT NULL,
  `node_id` varchar(128) NOT NULL,
  `revision` int NOT NULL DEFAULT '1',
  `attempt` int NOT NULL DEFAULT '1',
  `execution_id` varchar(255) NOT NULL,
  `status` varchar(32) NOT NULL,
  `handler_key` varchar(128) NOT NULL,
  `handler_version` varchar(64) NOT NULL,
  `input_json` text,
  `output_json` text,
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` text,
  `queued_at` timestamp NULL DEFAULT NULL,
  `started_at` timestamp NULL DEFAULT NULL,
  `heartbeat_at` timestamp NULL DEFAULT NULL,
  `finished_at` timestamp NULL DEFAULT NULL,
  `next_retry_at` timestamp NULL DEFAULT NULL,
  `worker_id` varchar(128) DEFAULT NULL,
  `lock_version` int NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `timeout_ms` int NOT NULL DEFAULT '30000',
  `duration_ms` int DEFAULT NULL,
  `contract_hash` varchar(64) DEFAULT NULL,
  `fencing_token` bigint NOT NULL DEFAULT '0',
  `budget_reservation_id` varchar(255) DEFAULT NULL,
  `reserved_input_tokens` bigint NOT NULL DEFAULT '0',
  `reserved_output_tokens` bigint NOT NULL DEFAULT '0',
  `reserved_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `reserved_model_calls` int NOT NULL DEFAULT '0',
  `actual_input_tokens` int NOT NULL DEFAULT '0',
  `actual_output_tokens` int NOT NULL DEFAULT '0',
  `actual_cache_tokens` int NOT NULL DEFAULT '0',
  `actual_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `actual_model_calls` int NOT NULL DEFAULT '0',
  `actual_tool_calls` int NOT NULL DEFAULT '0',
  `budget_status` varchar(32) DEFAULT NULL,
  `budget_settled_at` timestamp NULL DEFAULT NULL,
  `execution_bundle_hash` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_node_run_revision_attempt` (`workflow_run_id`,`node_id`,`revision`,`attempt`),
  UNIQUE KEY `uk_workflow_node_run_execution_id` (`execution_id`),
  KEY `idx_workflow_node_run_workflow_run_id` (`workflow_run_id`),
  KEY `idx_workflow_node_run_recovery` (`status`,`heartbeat_at`,`next_retry_at`),
  KEY `idx_workflow_node_run_contract_fence` (`execution_id`,`contract_hash`,`fencing_token`),
  KEY `idx_workflow_node_run_budget_reservation` (`budget_reservation_id`,`budget_status`),
  KEY `idx_workflow_node_run_bundle_hash` (`execution_bundle_hash`,`workflow_run_id`,`node_id`)
);

CREATE TABLE `workflow_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(128) NOT NULL,
  `aggregate_id` varchar(128) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `payload_json` text NOT NULL,
  `status` varchar(32) NOT NULL,
  `retry_count` int NOT NULL DEFAULT '0',
  `next_retry_at` timestamp NULL DEFAULT NULL,
  `published_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error_type` varchar(128) DEFAULT NULL,
  `last_error_at` timestamp NULL DEFAULT NULL,
  `dead_lettered_at` timestamp NULL DEFAULT NULL,
  `closed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_outbox_event_id` (`event_id`),
  KEY `idx_workflow_outbox_publish` (`status`,`next_retry_at`,`id`),
  KEY `idx_workflow_outbox_aggregate_status_id` (`aggregate_id`,`status`,`id`)
);

CREATE TABLE `workflow_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `operation` varchar(64) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `response_status` varchar(32) DEFAULT NULL,
  `response_percent` int DEFAULT NULL,
  `error_message` text,
  `started_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `correlation_id` varchar(64) DEFAULT NULL,
  `workflow_version_id` bigint DEFAULT NULL,
  `workflow_snapshot_json` text,
  `replay_of_run_id` bigint DEFAULT NULL,
  `review_round` int NOT NULL DEFAULT '0',
  `max_review_rounds` int NOT NULL DEFAULT '0',
  `lock_version` int NOT NULL DEFAULT '0',
  `last_heartbeat_at` timestamp NULL DEFAULT NULL,
  `accepted_duplicate_event_count` int NOT NULL DEFAULT '0',
  `quality_profile` varchar(32) NOT NULL DEFAULT 'BALANCED',
  `max_tokens` bigint DEFAULT NULL,
  `max_cost` decimal(14,6) DEFAULT NULL,
  `max_model_calls` int DEFAULT NULL,
  `max_wall_time_ms` bigint DEFAULT NULL,
  `consumed_tokens` bigint NOT NULL DEFAULT '0',
  `consumed_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `model_call_count` int NOT NULL DEFAULT '0',
  `reserved_tokens` bigint NOT NULL DEFAULT '0',
  `reserved_cost` decimal(14,6) NOT NULL DEFAULT '0.000000',
  `reserved_model_calls` int NOT NULL DEFAULT '0',
  `initiated_by_user_id` bigint DEFAULT NULL,
  `execution_bundle_id` bigint DEFAULT NULL,
  `execution_bundle_hash` varchar(64) DEFAULT NULL,
  `execution_bundle_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_run_idempotency` (`project_id`,`operation`,`idempotency_key`),
  KEY `idx_workflow_run_project_id` (`project_id`),
  KEY `idx_workflow_run_status` (`status`),
  KEY `idx_workflow_run_correlation_id` (`correlation_id`),
  KEY `idx_workflow_run_project_id_id` (`project_id`,`id`),
  KEY `idx_workflow_run_project_status_id` (`project_id`,`status`,`id`),
  KEY `idx_workflow_run_status_created_at` (`status`,`created_at`),
  KEY `idx_workflow_run_workflow_version_id` (`workflow_version_id`),
  KEY `idx_workflow_run_replay_of_run_id` (`replay_of_run_id`),
  KEY `idx_workflow_run_initiated_user_status` (`initiated_by_user_id`,`status`,`created_at`),
  KEY `idx_workflow_run_execution_bundle` (`execution_bundle_id`,`execution_bundle_hash`)
);

CREATE TABLE `workflow_tool_call_fact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(255) NOT NULL,
  `idempotency_key` varchar(255) NOT NULL,
  `execution_id` varchar(255) NOT NULL,
  `workflow_run_id` bigint NOT NULL,
  `node_run_id` bigint NOT NULL,
  `node_id` varchar(128) NOT NULL,
  `actor_user_id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `fencing_token` bigint NOT NULL,
  `deadline_epoch_ms` bigint NOT NULL,
  `execution_bundle_hash` varchar(64) DEFAULT NULL,
  `policy_hash` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `version` varchar(64) NOT NULL,
  `normalized_params_hash` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `result_json` longtext,
  `result_hash` varchar(64) DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `cached` tinyint(1) NOT NULL DEFAULT '0',
  `source_execution_id` varchar(255) DEFAULT NULL,
  `attempts` int NOT NULL DEFAULT '1',
  `duration_ms` int NOT NULL DEFAULT '0',
  `correlation_id` varchar(128) DEFAULT NULL,
  `traceparent` varchar(255) DEFAULT NULL,
  `tracestate` varchar(512) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_tool_call_request` (`request_id`),
  UNIQUE KEY `uk_workflow_tool_call_idempotency` (`idempotency_key`),
  KEY `idx_workflow_tool_call_execution` (`execution_id`,`created_at`),
  KEY `idx_workflow_tool_call_project` (`project_id`,`created_at`),
  KEY `idx_workflow_tool_call_status` (`status`,`created_at`)
);

CREATE TABLE `workflow_transition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_run_id` bigint NOT NULL,
  `node_run_id` bigint DEFAULT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `event_id` varchar(128) DEFAULT NULL,
  `metadata_json` text,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_workflow_transition_node_run` (`node_run_id`),
  KEY `idx_workflow_transition_run_id` (`workflow_run_id`,`id`)
);

CREATE TABLE `workflow_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `definition_id` bigint NOT NULL,
  `version` varchar(64) NOT NULL,
  `spec_json` text NOT NULL,
  `content_hash` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `published_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `immutable_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_version_definition_version` (`definition_id`,`version`),
  UNIQUE KEY `uk_workflow_version_content_hash` (`content_hash`),
  KEY `idx_workflow_version_definition_id` (`definition_id`),
  KEY `idx_workflow_version_immutable_at` (`status`,`immutable_at`,`id`)
);

ALTER TABLE `artifact` ADD CONSTRAINT `fk_artifact_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `artifact_component` ADD CONSTRAINT `fk_artifact_component_artifact` FOREIGN KEY (`artifact_id`) REFERENCES `artifact` (`id`);

ALTER TABLE `artifact_component` ADD CONSTRAINT `fk_artifact_component_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `artifact_trace_edge` ADD CONSTRAINT `fk_artifact_trace_artifact` FOREIGN KEY (`source_artifact_id`) REFERENCES `artifact` (`id`);

ALTER TABLE `artifact_trace_edge` ADD CONSTRAINT `fk_artifact_trace_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `audit_event` ADD CONSTRAINT `fk_audit_event_actor_user` FOREIGN KEY (`actor_user_id`) REFERENCES `user_account` (`id`);

ALTER TABLE `audit_event` ADD CONSTRAINT `fk_audit_event_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `code_generation_job` ADD CONSTRAINT `fk_code_generation_job_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `export_file` ADD CONSTRAINT `fk_export_file_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `knowledge_chunk` ADD CONSTRAINT `fk_knowledge_chunk_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`);

ALTER TABLE `knowledge_document` ADD CONSTRAINT `fk_knowledge_document_artifact` FOREIGN KEY (`artifact_id`) REFERENCES `artifact` (`id`);

ALTER TABLE `knowledge_document` ADD CONSTRAINT `fk_knowledge_document_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `model_invocation` ADD CONSTRAINT `fk_model_invocation_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `model_invocation` ADD CONSTRAINT `fk_model_invocation_workflow_run` FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`);

ALTER TABLE `project_member` ADD CONSTRAINT `fk_project_member_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `project_member` ADD CONSTRAINT `fk_project_member_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`);

ALTER TABLE `project_memory_fact` ADD CONSTRAINT `fk_project_memory_artifact` FOREIGN KEY (`source_artifact_id`) REFERENCES `artifact` (`id`);

ALTER TABLE `project_memory_fact` ADD CONSTRAINT `fk_project_memory_node_run` FOREIGN KEY (`source_node_run_id`) REFERENCES `workflow_node_run` (`id`);

ALTER TABLE `project_memory_fact` ADD CONSTRAINT `fk_project_memory_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `project_memory_fact` ADD CONSTRAINT `fk_project_memory_workflow_run` FOREIGN KEY (`source_workflow_run_id`) REFERENCES `workflow_run` (`id`);

ALTER TABLE `review_issue` ADD CONSTRAINT `fk_review_issue_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `user_session` ADD CONSTRAINT `fk_user_session_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`);

ALTER TABLE `workflow_approval` ADD CONSTRAINT `fk_workflow_approval_node_run` FOREIGN KEY (`node_run_id`) REFERENCES `workflow_node_run` (`id`);

ALTER TABLE `workflow_approval` ADD CONSTRAINT `fk_workflow_approval_run` FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`);

ALTER TABLE `workflow_execution_bundle` ADD CONSTRAINT `fk_workflow_execution_bundle_version` FOREIGN KEY (`workflow_version_id`) REFERENCES `workflow_version` (`id`);

ALTER TABLE `workflow_node_run` ADD CONSTRAINT `fk_workflow_node_run_workflow_run` FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`);

ALTER TABLE `workflow_run` ADD CONSTRAINT `fk_workflow_run_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);

ALTER TABLE `workflow_transition` ADD CONSTRAINT `fk_workflow_transition_node_run` FOREIGN KEY (`node_run_id`) REFERENCES `workflow_node_run` (`id`);

ALTER TABLE `workflow_transition` ADD CONSTRAINT `fk_workflow_transition_run` FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`);

ALTER TABLE `workflow_version` ADD CONSTRAINT `fk_workflow_version_definition` FOREIGN KEY (`definition_id`) REFERENCES `workflow_definition` (`id`);

INSERT INTO workflow_definition (workflow_key, name, description, status)
VALUES ('autospec-v5', 'AutoSpec', 'Parallel six-node workflow with verified delivery.', 'ACTIVE');

insert into workflow_version (
    definition_id, version, spec_json, content_hash, status, published_at, immutable_at, created_at
)
select definition.id,
       'pm-schema-repair-v12',
       '{
  "workflow_key": "autospec-v5",
  "version": "pm-schema-repair-v12",
  "protocol_version": 2,
  "runtime": {
    "max_parallel_nodes": 4,
    "max_review_rounds": 2,
    "default_timeout_ms": 60000
  },
  "nodes": [
    {
      "node_id": "product_manager",
      "agent_name": "ProductManagerAgent_v2",
      "input_schema": "GenerateRequest",
      "input_schema_hash": "bd917cb829f2341ec31c77868d3061b13c49ee965d99f83feb363bb3e6d808e8",
      "output_schema": "PrdArtifact",
      "output_schema_hash": "86e9680fe28afcd4f0c884c90ad8a52b8da54be00d0edbdcaf2ebb1a7329dd53",
      "artifact_type": "PRD",
      "prompt_key": "product_manager_schema",
      "prompt_version": "v1",
      "prompt_checksum": "a1372c35d03ce368f8106a5ec81678dd029c292306796c67cc880d42c471f25d",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 12000,
        "prompt_token_reserve": 3072,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "retrieval_policy",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 5000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "fast",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 8000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 120000,
      "depends_on": [],
      "approval": {
        "mode": "AFTER_NODE",
        "allowed_actions": [
          "APPROVE",
          "REJECT",
          "EDIT_AND_APPROVE"
        ]
      }
    },
    {
      "node_id": "architect",
      "agent_name": "ArchitectAgent_v3",
      "input_schema": "ArchitectureInput",
      "input_schema_hash": "358314862f4058c09511512f8dd8ac899e78b96cd28880431f6d41e1a0d44197",
      "output_schema": "ArchitectureDesignArtifactV2",
      "output_schema_hash": "62b1d1d44432e80f5ee1863837efc45c426635bd5618e20b53c8ed1fc3f4c729",
      "artifact_type": "ARCHITECTURE_DESIGN",
      "prompt_key": "architect_schema",
      "prompt_version": "v1",
      "prompt_checksum": "978363d93b3a82441815a09435e2cea448350ec062b4e1a643820f668e4e1c80",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 16000,
        "prompt_token_reserve": 6000,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 7000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "balanced",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 8000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "product_manager"
      ]
    },
    {
      "node_id": "backend_engineer",
      "agent_name": "BackendEngineerAgent_v6",
      "input_schema": "BackendDesignInput",
      "input_schema_hash": "90ed365ac8e9c15c75c6141fcca8cc006556b40bc0b88f8023f1de6bc1f77d41",
      "output_schema": "BackendDesignArtifact",
      "output_schema_hash": "2678cf7396c34995cf38731ca5da9626bcde8d438378efabdf1c21a736e290b0",
      "artifact_type": "BACKEND_DESIGN",
      "prompt_key": "backend_engineer_loop_v3",
      "prompt_version": "v1",
      "prompt_checksum": "2129ba20528487c4355133aa3e2d208424741dfba829e7e3134fb713f75fa569",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 24000,
        "prompt_token_reserve": 1500,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 8500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "balanced",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 5,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "tool_policy": {
        "version": "tools-v1",
        "enabled": true,
        "allowed_tools": [
          {
            "name": "spec.verify",
            "version": "v1"
          }
        ],
        "max_calls": 2,
        "per_call_timeout_ms": 30000,
        "total_timeout_ms": 30000,
        "max_result_bytes": 32000,
        "allowed_side_effects": [
          "SANDBOXED"
        ],
        "permission_policy": "workflow"
      },
      "agent_loop_policy": {
        "version": "agent-loop-v2",
        "enabled": true,
        "strategy": "plan-act-observe-validate-v1",
        "max_steps": 7,
        "max_replans": 1,
        "no_progress_limit": 1,
        "validator_profile": "backend-design-v2"
      },
      "verification_policy": {
        "enabled": true,
        "scope": "BACKEND",
        "required_level": "L1",
        "rule_profile": "spec-backend-v1",
        "verifier_version": "spec-verifier-v1",
        "compiler_version": "spec-compiler-v1",
        "timeout_ms": 30000,
        "policy_hash": "07f90333460fec2a396e2dabd8e275cfac90edf9e842af49083ff878f75e207e"
      },
      "timeout_ms": 60000,
      "depends_on": [
        "architect"
      ]
    },
    {
      "node_id": "frontend_engineer",
      "agent_name": "FrontendEngineerAgent_v3",
      "input_schema": "FrontendSkeletonInputV2",
      "input_schema_hash": "c64fc55ec5d53abec909ed8c53159cfa4a1418cf85142477e5498a66a524812b",
      "output_schema": "FrontendSkeletonArtifact",
      "output_schema_hash": "2490b7c0f0e5f8dfaaf62c4a2a8807f2f05d85ae75fb670fa0cbfbe9789e5dbd",
      "artifact_type": "FRONTEND_SKELETON",
      "prompt_key": "frontend_schema",
      "prompt_version": "v1",
      "prompt_checksum": "ce3301ab75f1572856630c8c40f030df4b6a4834837619f290375373b19ba79c",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 24000,
        "prompt_token_reserve": 2048,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 8500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "fast",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "architect"
      ]
    },
    {
      "node_id": "reviewer",
      "agent_name": "ReviewerAgent_v5",
      "input_schema": "ReviewInputV4",
      "input_schema_hash": "a7f3229e9dc9ad7fe825eba985328d9c55b5c3721946abe3a65c469c7686fd92",
      "output_schema": "ReviewReportV2",
      "output_schema_hash": "620549ea2a9afda11a3b07ddc10a8158770e65619690cc1193f2cb4241c90824",
      "artifact_type": "REVIEW_REPORT",
      "prompt_key": "reviewer_schema_v2",
      "prompt_version": "v1",
      "prompt_checksum": "462c89ca7b20c6f05969906e13311ca2626c128a2fa9f979a600e5f06624e8b7",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 30000,
        "prompt_token_reserve": 2048,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "backend_design",
          "frontend_skeleton",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 9500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "deep",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "backend_engineer",
        "frontend_engineer"
      ],
      "tool_policy": {
        "version": "tools-v1",
        "enabled": true,
        "allowed_tools": [
          {
            "name": "spec.verify",
            "version": "v1"
          }
        ],
        "max_calls": 1,
        "per_call_timeout_ms": 30000,
        "total_timeout_ms": 30000,
        "max_result_bytes": 32000,
        "allowed_side_effects": [
          "SANDBOXED"
        ],
        "permission_policy": "workflow"
      },
      "verification_policy": {
        "enabled": true,
        "scope": "FULL",
        "required_level": "L1",
        "rule_profile": "spec-full-v2",
        "verifier_version": "spec-verifier-v1",
        "compiler_version": "spec-compiler-v1",
        "timeout_ms": 30000,
        "policy_hash": "cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794"
      }
    },
    {
      "node_id": "evaluator",
      "agent_name": "EvaluatorAgent_v4",
      "input_schema": "EvaluationInputV3",
      "input_schema_hash": "2dcdb7bff37b4c1638210ce0e3d35b125eb7d9cc670243d8e1b6b4c0397cab83",
      "output_schema": "EvaluationReportV2",
      "output_schema_hash": "105c4913d48de2f875f8eafd17adc0870e1abb58d89eae44aeebfe0905ee9cf3",
      "artifact_type": "EVALUATION_REPORT",
      "prompt_key": "evaluator",
      "prompt_version": "v1",
      "prompt_checksum": "393abcdb2d663e41b8d7dfebe093f5f43fa4bdb6d3c46165ee6425aea3419fd2",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 40000,
        "prompt_token_reserve": 1500,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "artifacts",
          "review_report",
          "invocation_ledger",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 22000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "provider_key": "local",
        "model_name": "deterministic-rules",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 2000,
        "max_calls": 1,
        "input_cost_per_million": 0,
        "cached_input_cost_per_million": 0,
        "output_cost_per_million": 0,
        "required_capabilities": [
          "deterministic"
        ]
      },
      "retry_policy": {
        "max_attempts": 1
      },
      "timeout_ms": 60000,
      "depends_on": [
        "reviewer"
      ]
    }
  ],
  "edges": [
    {
      "from_node": "product_manager",
      "to_node": "architect"
    },
    {
      "from_node": "architect",
      "to_node": "backend_engineer"
    },
    {
      "from_node": "architect",
      "to_node": "frontend_engineer"
    },
    {
      "from_node": "backend_engineer",
      "to_node": "reviewer"
    },
    {
      "from_node": "frontend_engineer",
      "to_node": "reviewer"
    },
    {
      "from_node": "reviewer",
      "to_node": "evaluator"
    },
    {
      "from_node": "reviewer",
      "to_node": "architect",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    },
    {
      "from_node": "reviewer",
      "to_node": "backend_engineer",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    },
    {
      "from_node": "reviewer",
      "to_node": "frontend_engineer",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    }
  ]
}',
       '789e18119b2b0056565ef72ccb7492770b63a9cf161ac458f3b405e99155eaef',
       'PUBLISHED',
       now(),
       now(),
       now()
from workflow_definition definition
where definition.workflow_key = 'autospec-v5'
  and not exists (
      select 1 from workflow_version version
      where version.definition_id = definition.id
        and version.version = 'pm-schema-repair-v12'
  );
