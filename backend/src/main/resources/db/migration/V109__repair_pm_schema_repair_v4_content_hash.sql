-- Repair the draft candidate seeded by V108 with the backend's canonical JSON hash.
-- V108 remains immutable; this guard only updates the unpublished candidate.
update workflow_version
set content_hash = '9eab8b4b7fd0748917fbdbd32ba1fc97afb711cde4c9f190d76132bd72cd20e0'
where definition_id in (
          select id
          from workflow_definition
          where workflow_key = 'autospec-v5'
      )
  and version = 'pm-schema-repair-v4'
  and status = 'DRAFT';
