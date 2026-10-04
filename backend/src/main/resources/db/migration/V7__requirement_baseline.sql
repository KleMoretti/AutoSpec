CREATE TABLE `requirement_baseline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `baseline_id` varchar(128) NOT NULL,
  `project_id` bigint NOT NULL,
  `workflow_run_id` bigint NOT NULL,
  `version` int NOT NULL,
  `original_requirement` longtext NOT NULL,
  `answers_json` longtext NOT NULL,
  `assumptions_json` longtext NOT NULL,
  `conflict_resolutions_json` longtext NOT NULL,
  `context_conflicts_json` longtext NOT NULL,
  `scope_json` longtext NOT NULL,
  `constraints_json` longtext NOT NULL,
  `prd_artifact_id` bigint NOT NULL,
  `prd_version` int NOT NULL,
  `prd_content_hash` varchar(64) NOT NULL,
  `confirmed_by_user_id` bigint NOT NULL,
  `confirmed_at` timestamp NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_requirement_baseline_id` (`baseline_id`),
  UNIQUE KEY `uk_requirement_baseline_run_version` (`workflow_run_id`,`version`),
  KEY `idx_requirement_baseline_project` (`project_id`,`version`),
  KEY `idx_requirement_baseline_prd` (`prd_artifact_id`),
  CONSTRAINT `fk_requirement_baseline_project`
    FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_requirement_baseline_run`
    FOREIGN KEY (`workflow_run_id`) REFERENCES `workflow_run` (`id`),
  CONSTRAINT `fk_requirement_baseline_prd`
    FOREIGN KEY (`prd_artifact_id`) REFERENCES `artifact` (`id`),
  CONSTRAINT `fk_requirement_baseline_user`
    FOREIGN KEY (`confirmed_by_user_id`) REFERENCES `user_account` (`id`)
);
