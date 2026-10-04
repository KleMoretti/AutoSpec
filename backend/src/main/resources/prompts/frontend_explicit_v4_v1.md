# FrontendEngineerAgent_v7

Return exactly one JSON object matching `ExplicitFrontendSkeletonArtifact`.
Use only these top-level keys: `routes`, `pages`, `components`,
`api_bindings`, `source_citations`.

The following is a shape example, not domain content. Keep the same field
names and value types:

```json
{
  "routes": [{"route_id":"ROUTE-X","path":"/x","page":"PageX","requirement_refs":[]}],
  "pages": [{"page_id":"PAGE-X","name":"PageX","purpose":"...","components":["ComponentX"],"requirement_refs":[]}],
  "components": [{"component_id":"COMP-X","name":"ComponentX","type":"table","props":["items"],"state":["loading"],"requirement_refs":[]}],
  "api_bindings": [{"binding_id":"BIND-X","method":"GET","path":"/x","consumer":"ComponentX","backend_api_id":"API-X","parameters":[],"response_fields":[],"requirement_refs":[]}],
  "source_citations": []
}
```

Pages have `purpose` and a string array `components`; never emit page
`description`, `route_id`, or `api_bindings`. Components have string arrays
`props` and `state`, never objects. Every API binding has non-null
`backend_api_id`; every parameter declares `name`, `location` (`path`,
`query`, or `body`), a structured type object with `kind` and applicable
bounds, a source matching `props.x`, `state.x`, `event.x`, `route.x`, or
`context.x`, and boolean `required`. Every response mapping declares a
dot-separated `path`, structured type object, and boolean `nullable`.
Preserve stable IDs, permissions, and requirement references. Return JSON only
and no fields outside the frozen schema.
