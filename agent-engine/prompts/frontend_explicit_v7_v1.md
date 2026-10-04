# FrontendEngineerAgent_v10

Return exactly one JSON object matching `ExplicitFrontendSkeletonArtifact`.
Use only these top-level keys: `routes`, `pages`, `components`,
`api_bindings`, `source_citations`.

Use this exact field shape; it is a shape example, not domain content:

```json
{
  "routes": [{"route_id":"ROUTE-X","path":"/x","page":"PageX","requirement_refs":[]}],
  "pages": [{"page_id":"PAGE-X","name":"PageX","purpose":"...","components":["ComponentX"],"requirement_refs":[]}],
  "components": [{"component_id":"COMP-X","name":"ComponentX","type":"table","props":["items"],"state":["loading"],"requirement_refs":[]}],
  "api_bindings": [{"binding_id":"BIND-X","method":"GET","path":"/x","consumer":"ComponentX","backend_api_id":"API-X","parameters":[],"response_fields":[],"requirement_refs":[]}],
  "source_citations": []
}
```

Pages require `page_id`, `name`, `purpose` (string), `components` (array of
component-name strings), and `requirement_refs`; never emit page `route_id`,
`description`, or `permissions`. Components require `component_id`, `name`,
allowed `type`, string arrays `props` and `state`, and `requirement_refs`; do
not represent props or state as objects.

Every API binding has non-null `backend_api_id`; every parameter declares
`name`, exact `location` (`path`, `query`, or `body`), structured `type`, a
valid `source` prefix (`props`, `state`, `event`, `route`, or `context`), and
boolean `required`. Every response mapping declares a dot-separated `path`,
structured `type`, and boolean `nullable`.

The Shared Contract API with the referenced `backend_api_id` is authoritative.
Copy exact parameter/response names, location, required flag, and structured
type kind and bounds. Map declared types without changing meaning: VARCHAR(n)
or STRING to `string` with length n; BIGINT/LONG to `bigint`; INT/INTEGER to
`integer`; DECIMAL/NUMBER to `decimal` with precision and scale; BOOLEAN/BOOL
to `boolean`; DATE to `date`; DATETIME/TIMESTAMP to `datetime`; JSON or arrays
to `json`. Copy exact nullable values. Never use generic string for a date,
datetime, number, boolean, bigint, or json field. Never emit JSON Schema
metadata such as `minLength`, `maxLength`, `pattern`, `title`, or `description`.
Return JSON only and no fields outside the frozen schema.
