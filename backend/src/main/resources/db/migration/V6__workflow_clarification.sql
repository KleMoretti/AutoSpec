CREATE TABLE `workflow_clarification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_run_id` bigint NOT NULL,
  `node_run_id` bigint NOT NULL,
  `revision` int NOT NULL,
  `round` int NOT NULL,
  `request_id` varchar(128) NOT NULL,
  `request_json` longtext NOT NULL,
  `response_json` longtext,
  `status` varchar(32) NOT NULL,
  `approval_id` bigint NOT NULL,
  `lock_version` int NOT NULL DEFAULT '0',
  `idempotency_key` varchar(256) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `answered_at` timestamp NULL DEFAULT NULL,
  `expired_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_clarification_request` (`workflow_run_id`,`request_id`),
  UNIQUE KEY `uk_workflow_clarification_idempotency` (`workflow_run_id`,`idempotency_key`),
  KEY `idx_workflow_clarification_run_status` (`workflow_run_id`,`status`,`id`),
  KEY `idx_workflow_clarification_approval` (`approval_id`),
  CONSTRAINT `fk_workflow_clarification_run`
    FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`),
  CONSTRAINT `fk_workflow_clarification_node_run`
    FOREIGN KEY (`node_run_id`) REFERENCES `workflow_node_run` (`id`),
  CONSTRAINT `fk_workflow_clarification_approval`
    FOREIGN KEY (`approval_id`) REFERENCES `workflow_approval` (`id`)
);
