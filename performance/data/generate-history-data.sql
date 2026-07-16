-- Synthetic history generator for an isolated AutoSpec performance schema.
-- Required session variables are set by generate-history-data.ps1.

DROP PROCEDURE IF EXISTS autospec_generate_performance_history;

DELIMITER $$

CREATE PROCEDURE autospec_generate_performance_history()
BEGIN
    DECLARE v_owner_id BIGINT DEFAULT NULL;
    DECLARE v_anchor_project_id BIGINT DEFAULT NULL;
    DECLARE v_required_tables INT DEFAULT 0;
    DECLARE v_existing_dataset INT DEFAULT 0;
    DECLARE v_offset INT DEFAULT 0;
    DECLARE v_batch_rows INT DEFAULT 0;
    DECLARE v_dataset_name VARCHAR(128);
    DECLARE v_created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

    IF @autospec_perf_history_rows IS NULL
       OR @autospec_perf_history_rows < 1
       OR @autospec_perf_history_rows > 1000000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'history rows must be between 1 and 1000000';
    END IF;
    IF @autospec_perf_project_rows IS NULL
       OR @autospec_perf_project_rows < 1
       OR @autospec_perf_project_rows > 100000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'project rows must be between 1 and 100000';
    END IF;
    IF @autospec_perf_batch_size IS NULL
       OR @autospec_perf_batch_size < 100
       OR @autospec_perf_batch_size > 10000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'batch size must be between 100 and 10000';
    END IF;
    IF @autospec_perf_run_id IS NULL
       OR @autospec_perf_run_id NOT REGEXP '^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'invalid performance run id';
    END IF;
    IF DATABASE() IS NULL OR LOCATE('perf', LOWER(DATABASE())) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target database name must contain perf';
    END IF;

    SELECT COUNT(*)
      INTO v_required_tables
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name IN (
           'user_account',
           'project',
           'project_member',
           'workflow_run',
           'workflow_node_run',
           'artifact',
           'agent_event',
           'model_invocation'
       );
    IF v_required_tables <> 8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target schema is missing required migrated tables';
    END IF;

    SELECT MIN(id)
      INTO v_owner_id
      FROM user_account
     WHERE username = @autospec_perf_owner_username
       AND enabled = TRUE;
    IF v_owner_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'enabled owner account not found; log in once before generation';
    END IF;

    SET v_dataset_name = CONCAT('__AUTOSPEC_PERF_', @autospec_perf_run_id, '__');
    SELECT COUNT(*)
      INTO v_existing_dataset
      FROM project
     WHERE name = v_dataset_name;
    IF v_existing_dataset <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'performance run id already exists; use a new run id or recreate the schema';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS autospec_perf_numbers;
    CREATE TEMPORARY TABLE autospec_perf_numbers (
        n INT NOT NULL PRIMARY KEY
    );
    INSERT INTO autospec_perf_numbers (n)
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
      FROM (
          SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
      ) ones
      CROSS JOIN (
          SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
      ) tens
      CROSS JOIN (
          SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
      ) hundreds
      CROSS JOIN (
          SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
      ) thousands
     WHERE ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
           < @autospec_perf_batch_size;

    START TRANSACTION;
    INSERT INTO project (
        user_id,
        name,
        original_requirement,
        status,
        created_at,
        updated_at
    ) VALUES (
        v_owner_id,
        v_dataset_name,
        CONCAT('Synthetic performance anchor for ', @autospec_perf_run_id),
        'COMPLETED',
        v_created_at,
        v_created_at
    );
    SET v_anchor_project_id = LAST_INSERT_ID();

    INSERT INTO project_member (project_id, user_id, role, created_at)
    VALUES (v_anchor_project_id, v_owner_id, 'OWNER', v_created_at);
    COMMIT;

    SET v_offset = 1;
    WHILE v_offset < @autospec_perf_project_rows DO
        SET v_batch_rows = LEAST(
            @autospec_perf_batch_size,
            @autospec_perf_project_rows - v_offset
        );
        START TRANSACTION;
        INSERT INTO project (
            user_id,
            name,
            original_requirement,
            status,
            created_at,
            updated_at
        )
        SELECT
            v_owner_id,
            CONCAT(
                '__AUTOSPEC_PERF_',
                @autospec_perf_run_id,
                '_P_',
                LPAD(v_offset + n, 10, '0')
            ),
            'Synthetic project-list performance fixture',
            'COMPLETED',
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at)
          FROM autospec_perf_numbers
         WHERE n < v_batch_rows;
        COMMIT;
        SET v_offset = v_offset + v_batch_rows;
    END WHILE;

    SET v_offset = 0;
    WHILE v_offset < @autospec_perf_history_rows DO
        SET v_batch_rows = LEAST(
            @autospec_perf_batch_size,
            @autospec_perf_history_rows - v_offset
        );
        START TRANSACTION;

        INSERT INTO workflow_run (
            project_id,
            operation,
            idempotency_key,
            status,
            response_status,
            response_percent,
            started_at,
            completed_at,
            created_at,
            updated_at
        )
        SELECT
            v_anchor_project_id,
            'PERF_FIXTURE',
            CONCAT('perf-', @autospec_perf_run_id, '-', LPAD(v_offset + n, 10, '0')),
            'COMPLETED',
            'COMPLETED',
            100,
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at)
          FROM autospec_perf_numbers
         WHERE n < v_batch_rows;

        INSERT INTO workflow_node_run (
            workflow_run_id,
            node_id,
            revision,
            attempt,
            execution_id,
            status,
            handler_key,
            handler_version,
            input_json,
            output_json,
            queued_at,
            started_at,
            finished_at,
            created_at,
            updated_at
        )
        SELECT
            run.id,
            'perf_node',
            1,
            1,
            CONCAT('perf-exec-', @autospec_perf_run_id, '-', LPAD(v_offset + numbers.n, 10, '0')),
            'SUCCEEDED',
            'FixtureAgent',
            'v1',
            '{"synthetic":true}',
            '{"synthetic":true}',
            run.created_at,
            run.created_at,
            run.completed_at,
            run.created_at,
            run.updated_at
          FROM autospec_perf_numbers numbers
          JOIN workflow_run run
            ON run.project_id = v_anchor_project_id
           AND run.operation = 'PERF_FIXTURE'
           AND run.idempotency_key = CONCAT(
               'perf-',
               @autospec_perf_run_id,
               '-',
               LPAD(v_offset + numbers.n, 10, '0')
           )
         WHERE numbers.n < v_batch_rows;

        INSERT INTO artifact (
            project_id,
            type,
            title,
            content,
            format,
            version,
            status,
            source_agent,
            created_at,
            updated_at
        )
        SELECT
            v_anchor_project_id,
            'PERF_SYNTHETIC',
            CONCAT('Synthetic artifact ', LPAD(v_offset + n, 10, '0')),
            CONCAT(
                '{"synthetic":true,"runId":"',
                @autospec_perf_run_id,
                '","sequence":',
                v_offset + n,
                '}'
            ),
            'JSON',
            1,
            'GENERATED',
            'PerformanceFixture',
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at)
          FROM autospec_perf_numbers
         WHERE n < v_batch_rows;

        INSERT INTO agent_event (
            project_id,
            task_id,
            event_type,
            node_name,
            message,
            payload,
            created_at
        )
        SELECT
            v_anchor_project_id,
            NULL,
            'PERF_SYNTHETIC',
            'perf_node',
            CONCAT('Synthetic event ', LPAD(v_offset + n, 10, '0')),
            CONCAT('{"synthetic":true,"sequence":', v_offset + n, '}'),
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at)
          FROM autospec_perf_numbers
         WHERE n < v_batch_rows;

        INSERT INTO model_invocation (
            project_id,
            task_id,
            provider_key,
            model_name,
            agent_node,
            prompt_version_id,
            status,
            duration_ms,
            input_tokens,
            output_tokens,
            estimated_cost,
            score,
            error_message,
            created_at
        )
        SELECT
            v_anchor_project_id,
            NULL,
            'fixture',
            'fixture-model',
            'perf_node',
            NULL,
            'SUCCEEDED',
            0,
            0,
            0,
            0.000000,
            NULL,
            NULL,
            TIMESTAMPADD(SECOND, -MOD(v_offset + n, 2592000), v_created_at)
          FROM autospec_perf_numbers
         WHERE n < v_batch_rows;

        COMMIT;
        SET v_offset = v_offset + v_batch_rows;
    END WHILE;

    SELECT
        @autospec_perf_run_id AS performance_run_id,
        v_anchor_project_id AS anchor_project_id,
        @autospec_perf_project_rows AS requested_project_rows,
        @autospec_perf_history_rows AS requested_rows_per_history_table;

    SELECT
        MIN(id) AS minimum_workflow_run_id,
        MAX(id) AS maximum_workflow_run_id
      FROM workflow_run
     WHERE project_id = v_anchor_project_id
       AND operation = 'PERF_FIXTURE';

    SELECT 'project' AS table_name, COUNT(*) AS row_count
      FROM project
     WHERE user_id = v_owner_id
       AND name LIKE CONCAT('__AUTOSPEC_PERF_', @autospec_perf_run_id, '%')
    UNION ALL
    SELECT 'workflow_run', COUNT(*)
      FROM workflow_run
     WHERE project_id = v_anchor_project_id
       AND operation = 'PERF_FIXTURE'
    UNION ALL
    SELECT 'workflow_node_run', COUNT(*)
      FROM workflow_node_run node
      JOIN workflow_run run ON run.id = node.workflow_run_id
     WHERE run.project_id = v_anchor_project_id
       AND run.operation = 'PERF_FIXTURE'
    UNION ALL
    SELECT 'artifact', COUNT(*)
      FROM artifact
     WHERE project_id = v_anchor_project_id
       AND type = 'PERF_SYNTHETIC'
    UNION ALL
    SELECT 'agent_event', COUNT(*)
      FROM agent_event
     WHERE project_id = v_anchor_project_id
       AND event_type = 'PERF_SYNTHETIC'
    UNION ALL
    SELECT 'model_invocation', COUNT(*)
      FROM model_invocation
     WHERE project_id = v_anchor_project_id
       AND provider_key = 'fixture';

    DROP TEMPORARY TABLE IF EXISTS autospec_perf_numbers;
END$$

DELIMITER ;

CALL autospec_generate_performance_history();
DROP PROCEDURE IF EXISTS autospec_generate_performance_history;
