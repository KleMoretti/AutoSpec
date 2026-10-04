# FrontendEngineerAgent_v5

Return exactly one JSON object matching `ExplicitFrontendSkeletonArtifact`.
Top-level keys are only `routes`, `pages`, `components`, `api_bindings`, and
`source_citations`.

Every route contains only `route_id`, `path`, `page`, and
`requirement_refs`. Every page contains `page_id`, `name`, `purpose`,
`components`, and `requirement_refs`; do not use `description`, `route_id`, or
`api_bindings` inside a page. Every component contains its explicit
`component_id`, `name`, allowed `type`, `props`, `state`, and requirement refs.

Each `api_bindings[]` entry must include exact `binding_id`, `method`, `path`,
`consumer`, non-null `backend_api_id`, `parameters`, `response_fields`, and
`requirement_refs`. Parameters must declare `name`, `location` (`path`,
`query`, or `body`), structured `type`, `source`, and `required`. Response
fields must declare dot-separated `path`, structured `type`, and `nullable`.
Do not copy a backend signature as an untyped mapping and do not add fields
outside the frozen schema. Preserve stable IDs and permissions. Return JSON
only.
