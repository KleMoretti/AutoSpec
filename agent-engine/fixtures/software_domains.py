"""Small deterministic software-domain fixtures.

The fixture is deliberately shared by all five artifact-producing nodes.  It
is used only when the worker is running without a model client; a live model
request never silently falls back to one of these examples.
"""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any

from schemas.architecture_design import (
    ArchitectureDesignArtifact,
    ArchitectureDesignArtifactV2,
)
from schemas.backend_design import (
    BackendDesignArtifact,
    ExplicitBackendDesignArtifact,
)
from schemas.frontend_skeleton import (
    ExplicitFrontendSkeletonArtifact,
    FrontendSkeletonArtifact,
)
from schemas.prd import PrdArtifact


SUPPORTED_DOMAIN_KEYS = (
    "campus_marketplace",
    "inventory_management",
    "employee_leave_approval",
)


@dataclass(frozen=True)
class SoftwareDomainFixture:
    key: str
    display_name: str
    prd: PrdArtifact
    architecture: ArchitectureDesignArtifact
    backend: BackendDesignArtifact
    frontend: FrontendSkeletonArtifact

    def shared_architecture(self) -> ArchitectureDesignArtifactV2:
        """Return the same architecture with an internally consistent contract."""

        api_signatures = self.backend.apis
        domain_models = [
            {
                "name": table.name,
                "fields": [field.model_dump(mode="json") for field in table.fields],
            }
            for table in self.backend.tables
        ]
        dtos = [
            {
                "name": f"{api.api_id}Response",
                "fields": [
                    {
                        "name": field.name,
                        "type": field.type,
                        "nullable": False,
                        "description": field.description,
                    }
                    for field in api.response_fields
                ],
            }
            for api in api_signatures
        ]
        return ArchitectureDesignArtifactV2.model_validate(
            {
                **self.architecture.model_dump(mode="json"),
                "shared_contract": {
                    "version": "shared-contract-v1",
                    "domain_models": domain_models,
                    "api_signatures": [
                        api.model_dump(mode="json") for api in api_signatures
                    ],
                    "dtos": dtos,
                    "error_codes": [
                        {
                            "code": "ACCESS_DENIED",
                            "http_status": 403,
                            "meaning": "Actor lacks the required role",
                        },
                        {
                            "code": "VALIDATION_FAILED",
                            "http_status": 400,
                            "meaning": "The request does not satisfy the domain contract",
                        },
                    ],
                    "permission_matrix": [
                        {
                            "api_id": api.api_id,
                            "roles": api.required_roles,
                            "auth_required": api.auth_required,
                        }
                        for api in api_signatures
                    ],
                },
            }
        )


def fixture_key_for_requirement(requirement: str, explicit_key: str | None = None) -> str:
    """Resolve a documented demo key without affecting live model routing.

    The fallback for an unrecognised requirement is the historical marketplace
    fixture.  Callers can distinguish that case with ``is_supported_requirement``
    if they need to show a demo-data warning to a user.
    """

    if explicit_key is not None:
        if explicit_key not in SUPPORTED_DOMAIN_KEYS:
            raise ValueError(f"unsupported fixture key: {explicit_key}")
        return explicit_key
    text = (requirement or "").casefold()
    if any(term in text for term in ("inventory", "stock", "warehouse", "库存", "存货")):
        return "inventory_management"
    if any(
        term in text
        for term in (
            "leave",
            "vacation",
            "absence",
            "time off",
            "请假",
            "休假",
        )
    ):
        return "employee_leave_approval"
    return "campus_marketplace"


def is_supported_requirement(requirement: str) -> bool:
    text = (requirement or "").casefold()
    return any(
        term in text
        for term in (
            "marketplace",
            "second-hand",
            "second hand",
            "campus",
            "inventory",
            "stock",
            "warehouse",
            "库存",
            "leave",
            "vacation",
            "absence",
            "请假",
            "休假",
        )
    )


def get_fixture(key: str) -> SoftwareDomainFixture:
    try:
        fixture = _FIXTURES[key]
        # Each node/test receives an isolated artifact graph.  Returning the
        # module-level Pydantic instances directly would let reviewer tests or
        # rework mutate the next deterministic run's baseline.
        return SoftwareDomainFixture(
            key=fixture.key,
            display_name=fixture.display_name,
            prd=fixture.prd.model_copy(deep=True),
            architecture=fixture.architecture.model_copy(deep=True),
            backend=fixture.backend.model_copy(deep=True),
            frontend=fixture.frontend.model_copy(deep=True),
        )
    except KeyError as exc:
        raise ValueError(f"unsupported fixture key: {key}") from exc


def fixture_for_requirement(
    requirement: str, explicit_key: str | None = None
) -> SoftwareDomainFixture:
    return get_fixture(fixture_key_for_requirement(requirement, explicit_key))


_EXPLICIT_FOREIGN_KEYS: dict[str, dict[tuple[str, str], dict[str, str]]] = {
    "campus_marketplace": {
        ("product", "seller_id"): {"table": "user_account", "field": "id"},
        ("favorite", "product_id"): {"table": "product", "field": "id"},
    },
    "inventory_management": {
        ("stock_event", "item_id"): {"table": "inventory_item", "field": "id"},
    },
    "employee_leave_approval": {
        ("leave_approval", "leave_request_id"): {
            "table": "leave_request",
            "field": "id",
        },
    },
}


_EXPLICIT_PARAMETER_LOCATIONS: dict[str, dict[str, dict[str, str]]] = {
    "campus_marketplace": {
        "API-PRODUCT-CREATE": {"title": "body"},
        "API-PRODUCT-SEARCH": {"keyword": "query"},
        "API-FAVORITE-CREATE": {"productId": "body"},
        "API-PRODUCT-AUDIT": {"productId": "path", "decision": "body"},
    },
    "inventory_management": {
        "API-STOCK-GET": {"itemId": "path"},
        "API-STOCK-EVENT": {"itemId": "body", "eventId": "body", "delta": "body"},
    },
    "employee_leave_approval": {
        "API-LEAVE-CREATE": {"startDate": "body", "endDate": "body"},
        "API-LEAVE-APPROVE": {
            "requestId": "path",
            "decision": "body",
            "reason": "body",
        },
    },
}


def explicit_backend_for_fixture(
    fixture: SoftwareDomainFixture,
) -> ExplicitBackendDesignArtifact:
    """Return hand-authored explicit facts for deterministic fixture mode.

    This helper is only used by the no-model fixture branch.  Live model
    output is parsed directly by ``ExplicitBackendDesignArtifact`` and never
    passes through this enrichment path.
    """

    payload = fixture.backend.model_dump(mode="json")
    foreign_keys = _EXPLICIT_FOREIGN_KEYS[fixture.key]
    locations = _EXPLICIT_PARAMETER_LOCATIONS[fixture.key]
    for table in payload["tables"]:
        for field in table["fields"]:
            field["type"] = _fixture_explicit_type(field["type"])
            field["primary_key"] = field["name"] == "id"
            field["unique"] = False
            field["foreign_key"] = foreign_keys.get((table["name"], field["name"]))
    for api in payload["apis"]:
        api["success_status"] = 200
        api_locations = locations[api["api_id"]]
        for parameter in api["request_params"]:
            parameter["type"] = _fixture_explicit_type(parameter["type"])
            parameter["location"] = api_locations[parameter["name"]]
        for response in api["response_fields"]:
            response["type"] = _fixture_explicit_type(response["type"])
            response["nullable"] = False
    return ExplicitBackendDesignArtifact.model_validate(payload)


def explicit_frontend_for_fixture(
    fixture: SoftwareDomainFixture,
    backend: ExplicitBackendDesignArtifact | None = None,
) -> ExplicitFrontendSkeletonArtifact:
    """Return explicit, independent request/response claims for fixture mode."""

    backend = backend or explicit_backend_for_fixture(fixture)
    payload = fixture.frontend.model_dump(mode="json")
    api_by_id = {api.api_id: api for api in backend.apis}
    for binding in payload["api_bindings"]:
        api = api_by_id[binding["backend_api_id"]]
        binding["parameters"] = [
            {
                "name": parameter.name,
                "location": parameter.location,
                "source": (
                    f"route.{parameter.name}"
                    if parameter.location == "path"
                    else f"state.{parameter.name}"
                ),
                "type": parameter.type.model_dump(mode="json"),
                "required": parameter.required,
            }
            for parameter in api.request_params
        ]
        binding["response_fields"] = [
            {
                "path": response.name,
                "type": response.type.model_dump(mode="json"),
                "nullable": response.nullable,
            }
            for response in api.response_fields
        ]
    return ExplicitFrontendSkeletonArtifact.model_validate(payload)


def _fixture_explicit_type(value: str) -> dict[str, Any]:
    upper = value.upper()
    if upper.startswith("VARCHAR"):
        match = re.fullmatch(r"VARCHAR\((\d+)\)", upper)
        if match is None:
            raise ValueError(f"fixture type is not supported: {value}")
        return {"kind": "string", "length": int(match.group(1))}
    if upper in {"BIGINT", "LONG", "NUMBER"}:
        return {"kind": "bigint"}
    if upper in {"INT", "INTEGER"}:
        return {"kind": "integer"}
    if upper.startswith("DECIMAL"):
        match = re.fullmatch(r"DECIMAL\((\d+),(\d+)\)", upper)
        if match is None:
            raise ValueError(f"fixture type is not supported: {value}")
        return {
            "kind": "decimal",
            "precision": int(match.group(1)),
            "scale": int(match.group(2)),
        }
    if upper in {"BOOLEAN", "BOOL"}:
        return {"kind": "boolean"}
    if upper == "DATE":
        return {"kind": "date"}
    if upper in {"DATETIME", "TIMESTAMP"}:
        return {"kind": "datetime"}
    if upper == "JSON" or upper.endswith("[]"):
        return {"kind": "json"}
    if upper == "STRING":
        return {"kind": "string", "length": 255}
    raise ValueError(f"fixture type is not supported: {value}")


def _prd(
    project_name: str,
    users: list[str],
    features: list[dict[str, Any]],
    stories: list[dict[str, Any]],
    boundaries: list[str],
    risks: list[str],
) -> PrdArtifact:
    return PrdArtifact.model_validate(
        {
            "project_name": project_name,
            "target_users": users,
            "core_features": features,
            "user_stories": stories,
            "business_boundaries": boundaries,
            "non_functional_requirements": [
                "Every artifact is structured JSON with stable component identifiers.",
                "The generated contract records authorization and traceability facts.",
            ],
            "risks": risks,
        }
    )


def _story(
    story_id: str,
    role: str,
    goal: str,
    benefit: str,
    refs: list[str],
    acceptance: list[tuple[str, str]],
) -> dict[str, Any]:
    return {
        "story_id": story_id,
        "role": role,
        "goal": goal,
        "benefit": benefit,
        "requirement_refs": refs,
        "acceptance_criteria": [
            {
                "acceptance_id": acceptance_id,
                "criterion": criterion,
                "requirement_refs": refs,
            }
            for acceptance_id, criterion in acceptance
        ],
    }


def _backend(tables: list[dict[str, Any]], apis: list[dict[str, Any]]) -> BackendDesignArtifact:
    return BackendDesignArtifact.model_validate({"tables": tables, "apis": apis})


def _architecture(
    name: str,
    modules: list[dict[str, Any]],
    decisions: list[dict[str, Any]],
    risks: list[str],
) -> ArchitectureDesignArtifact:
    return ArchitectureDesignArtifact.model_validate(
        {
            "system_context": f"{name} domain services expose a typed API and durable audit trail.",
            "modules": modules,
            "decisions": decisions,
            "non_functional_constraints": [
                {
                    "constraint_id": "NFR-TRACE",
                    "category": "traceability",
                    "requirement": "Stable requirement and component IDs are carried across artifacts.",
                    "requirement_refs": [],
                }
            ],
            "integration_risks": risks,
        }
    )


def _frontend(
    routes: list[dict[str, Any]],
    pages: list[dict[str, Any]],
    components: list[dict[str, Any]],
    bindings: list[dict[str, Any]],
) -> FrontendSkeletonArtifact:
    return FrontendSkeletonArtifact.model_validate(
        {
            "routes": routes,
            "pages": pages,
            "components": components,
            "api_bindings": bindings,
        }
    )


def _marketplace() -> SoftwareDomainFixture:
    features = [
        {"requirement_id": "REQ-PUBLISH", "name": "Product publishing", "description": "Students publish structured second-hand listings.", "priority": "MUST"},
        {"requirement_id": "REQ-SEARCH", "name": "Product search", "description": "Students search and browse approved campus listings.", "priority": "MUST"},
        {"requirement_id": "REQ-FAVORITE", "name": "Favorite products", "description": "Students save products they are interested in.", "priority": "SHOULD"},
        {"requirement_id": "REQ-AUDIT", "name": "Admin audit", "description": "Admins approve or reject listings before visibility.", "priority": "MUST"},
    ]
    prd = _prd(
        "Campus Second-Hand Marketplace",
        ["student", "admin"],
        features,
        [
            _story("STORY-PUBLISH", "student", "publish an idle item", "find a buyer on campus", ["REQ-PUBLISH"], [("AC-PUBLISH-DETAILS", "The listing stores title, price, category, description, and images."), ("AC-PUBLISH-PENDING", "The listing enters a pending audit state after submission.")]),
            _story("STORY-SEARCH", "student", "search approved listings", "find relevant products on campus", ["REQ-SEARCH"], [("AC-SEARCH-KEYWORD", "Students can filter listings by a keyword."), ("AC-SEARCH-APPROVED", "Search results exclude listings that are not approved.")]),
            _story("STORY-AUDIT", "admin", "audit product listings", "keep prohibited goods out of the marketplace", ["REQ-AUDIT"], [("AC-AUDIT-ROLE", "Only admins can approve or reject pending listings."), ("AC-AUDIT-REASON", "Rejected listings include a visible reason.")]),
        ],
        ["Payment escrow and off-campus logistics are outside this fixture."],
        ["Listings may contain prohibited goods.", "API and data designs can drift without structural review."],
    )
    backend = _backend(
        [
            {"table_id": "TABLE-USER", "name": "user_account", "description": "Campus user and role.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "role", "type": "VARCHAR(32)", "nullable": False, "description": "STUDENT or ADMIN."}], "requirement_refs": ["REQ-PUBLISH", "REQ-AUDIT"]},
            {"table_id": "TABLE-PRODUCT", "name": "product", "description": "Second-hand listing.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "seller_id", "type": "BIGINT", "nullable": False, "description": "Seller user id."}, {"name": "title", "type": "VARCHAR(128)", "nullable": False, "description": "Listing title."}, {"name": "audit_status", "type": "VARCHAR(32)", "nullable": False, "description": "PENDING, APPROVED, or REJECTED."}], "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-AUDIT"]},
            {"table_id": "TABLE-FAVORITE", "name": "favorite", "description": "Saved product relation.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "product_id", "type": "BIGINT", "nullable": False, "description": "Saved product."}], "requirement_refs": ["REQ-FAVORITE"]},
        ],
        [
            {"api_id": "API-PRODUCT-CREATE", "method": "POST", "path": "/api/products", "description": "Publish a product.", "request_params": [{"name": "title", "type": "string", "required": True, "description": "Listing title."}], "response_fields": [{"name": "productId", "type": "number", "description": "Created product id."}], "auth_required": True, "required_roles": ["STUDENT"], "requirement_refs": ["REQ-PUBLISH"]},
            {"api_id": "API-PRODUCT-SEARCH", "method": "GET", "path": "/api/products", "description": "Search approved products.", "request_params": [{"name": "keyword", "type": "string", "required": False, "description": "Search keyword."}], "response_fields": [{"name": "items", "type": "ProductSummary[]", "description": "Matched products."}], "auth_required": True, "required_roles": ["STUDENT"], "requirement_refs": ["REQ-SEARCH"]},
            {"api_id": "API-FAVORITE-CREATE", "method": "POST", "path": "/api/favorites", "description": "Favorite a product.", "request_params": [{"name": "productId", "type": "number", "required": True, "description": "Target product."}], "response_fields": [{"name": "favoriteId", "type": "number", "description": "Created favorite id."}], "auth_required": True, "required_roles": ["STUDENT"], "requirement_refs": ["REQ-FAVORITE"]},
            {"api_id": "API-PRODUCT-AUDIT", "method": "POST", "path": "/api/admin/products/{productId}/audit", "description": "Admin audit endpoint.", "request_params": [{"name": "productId", "type": "number", "required": True, "description": "Product id."}, {"name": "decision", "type": "string", "required": True, "description": "APPROVE or REJECT."}], "response_fields": [{"name": "auditStatus", "type": "string", "description": "Updated status."}], "auth_required": True, "required_roles": ["ADMIN"], "requirement_refs": ["REQ-AUDIT"]},
        ],
    )
    architecture = _architecture("Campus marketplace", [{"module_id": "MOD-DOMAIN", "name": "marketplace", "responsibility": "Own listings, search, favorites, and business audit.", "depends_on": [], "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE", "REQ-AUDIT"]}], [{"decision_id": "ADR-AUDIT", "title": "Pending audit state", "context": "Listings need moderation.", "decision": "Persist audit status before public search.", "consequences": ["Search can filter approved records."], "requirement_refs": ["REQ-AUDIT"]}], ["Audit state must remain consistent with search visibility."])
    frontend = _frontend(
        [{"route_id": "ROUTE-PRODUCTS", "path": "/products", "page": "MarketplacePage", "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE"]}, {"route_id": "ROUTE-ADMIN", "path": "/admin/audit", "page": "AdminAuditPage", "requirement_refs": ["REQ-AUDIT"]}],
        [{"page_id": "PAGE-MARKETPLACE", "name": "MarketplacePage", "purpose": "Publish, search, and favorite listings.", "components": ["ProductForm", "ProductList", "FavoriteButton"], "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE"]}, {"page_id": "PAGE-ADMIN", "name": "AdminAuditPage", "purpose": "Approve or reject pending listings.", "components": ["AuditTable"], "requirement_refs": ["REQ-AUDIT"]}],
        [{"component_id": "COMP-FORM", "name": "ProductForm", "type": "form", "props": ["onSubmit"], "state": ["draft", "error"], "requirement_refs": ["REQ-PUBLISH"]}, {"component_id": "COMP-LIST", "name": "ProductList", "type": "table", "props": ["items", "onSearch"], "state": ["keyword", "loading"], "requirement_refs": ["REQ-SEARCH"]}, {"component_id": "COMP-FAVORITE", "name": "FavoriteButton", "type": "toolbar", "props": ["productId"], "state": ["saving"], "requirement_refs": ["REQ-FAVORITE"]}, {"component_id": "COMP-AUDIT", "name": "AuditTable", "type": "table", "props": ["pending", "onDecide"], "state": ["submitting"], "requirement_refs": ["REQ-AUDIT"]}],
        [{"binding_id": "BIND-CREATE", "method": "POST", "path": "/api/products", "consumer": "ProductForm", "backend_api_id": "API-PRODUCT-CREATE", "requirement_refs": ["REQ-PUBLISH"]}, {"binding_id": "BIND-SEARCH", "method": "GET", "path": "/api/products", "consumer": "ProductList", "backend_api_id": "API-PRODUCT-SEARCH", "requirement_refs": ["REQ-SEARCH"]}, {"binding_id": "BIND-FAVORITE", "method": "POST", "path": "/api/favorites", "consumer": "FavoriteButton", "backend_api_id": "API-FAVORITE-CREATE", "requirement_refs": ["REQ-FAVORITE"]}, {"binding_id": "BIND-AUDIT", "method": "POST", "path": "/api/admin/products/{productId}/audit", "consumer": "AuditTable", "backend_api_id": "API-PRODUCT-AUDIT", "requirement_refs": ["REQ-AUDIT"]}],
    )
    return SoftwareDomainFixture("campus_marketplace", "Campus marketplace", prd, architecture, backend, frontend)


def _inventory() -> SoftwareDomainFixture:
    features = [{"requirement_id": "REQ-STOCK", "name": "Stock levels", "description": "Operators maintain item quantities and reorder thresholds.", "priority": "MUST"}, {"requirement_id": "REQ-EVENT", "name": "Stock events", "description": "Business event records explain receipts and adjustments.", "priority": "MUST"}, {"requirement_id": "REQ-RETRY", "name": "Safe retry", "description": "A repeated stock event is idempotent.", "priority": "SHOULD"}]
    prd = _prd("Inventory Management", ["operator", "manager"], features, [_story("STORY-STOCK", "operator", "update stock", "keep counts accurate", ["REQ-STOCK", "REQ-EVENT"], [("AC-STOCK-COUNT", "An item exposes quantity and reorder threshold."), ("AC-STOCK-EVENT", "Each adjustment records a business event id.")]), _story("STORY-RETRY", "operator", "retry an adjustment", "avoid duplicate movement", ["REQ-RETRY"], [("AC-RETRY-IDEMPOTENT", "Repeating the same event id does not double-apply quantity.")])], ["Supplier billing is outside this fixture."], ["Concurrent adjustments need a unique business event id."])
    backend = _backend([{"table_id": "TABLE-ITEM", "name": "inventory_item", "description": "Stocked item.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "sku", "type": "VARCHAR(64)", "nullable": False, "description": "Stock keeping unit."}, {"name": "quantity", "type": "INTEGER", "nullable": False, "description": "Current quantity."}, {"name": "reorder_threshold", "type": "INTEGER", "nullable": False, "description": "Reorder threshold."}], "requirement_refs": ["REQ-STOCK"]}, {"table_id": "TABLE-EVENT", "name": "stock_event", "description": "Business stock movement event.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "item_id", "type": "BIGINT", "nullable": False, "description": "Inventory item."}, {"name": "event_id", "type": "VARCHAR(64)", "nullable": False, "description": "Idempotency key for a business event."}, {"name": "delta", "type": "INTEGER", "nullable": False, "description": "Quantity change."}], "requirement_refs": ["REQ-EVENT", "REQ-RETRY"]}], [{"api_id": "API-STOCK-GET", "method": "GET", "path": "/api/inventory/items/{itemId}", "description": "Read one stock level.", "request_params": [{"name": "itemId", "type": "number", "required": True, "description": "Item id."}], "response_fields": [{"name": "quantity", "type": "number", "description": "Current quantity."}], "auth_required": True, "required_roles": ["OPERATOR", "MANAGER"], "requirement_refs": ["REQ-STOCK"]}, {"api_id": "API-STOCK-EVENT", "method": "POST", "path": "/api/inventory/events", "description": "Apply a stock business event with retry-safe event id.", "request_params": [{"name": "itemId", "type": "number", "required": True, "description": "Item id."}, {"name": "eventId", "type": "string", "required": True, "description": "Business event id."}, {"name": "delta", "type": "number", "required": True, "description": "Quantity change."}], "response_fields": [{"name": "applied", "type": "boolean", "description": "Whether this event changed stock."}], "auth_required": True, "required_roles": ["OPERATOR"], "requirement_refs": ["REQ-EVENT", "REQ-RETRY"]}])
    architecture = _architecture("Inventory", [{"module_id": "MOD-INVENTORY", "name": "inventory", "responsibility": "Own item quantities and stock event idempotency.", "depends_on": [], "requirement_refs": ["REQ-STOCK", "REQ-EVENT", "REQ-RETRY"]}], [{"decision_id": "ADR-EVENT-ID", "title": "Business event idempotency", "context": "Retries can duplicate delivery.", "decision": "Use a unique event id before applying delta.", "consequences": ["A retry returns the existing application result."], "requirement_refs": ["REQ-RETRY"]}], ["The event ledger and item update must commit atomically."])
    frontend = _frontend([{"route_id": "ROUTE-STOCK", "path": "/inventory", "page": "InventoryPage", "requirement_refs": ["REQ-STOCK", "REQ-EVENT", "REQ-RETRY"]}], [{"page_id": "PAGE-STOCK", "name": "InventoryPage", "purpose": "View stock and submit adjustments.", "components": ["StockTable", "AdjustmentForm"], "requirement_refs": ["REQ-STOCK", "REQ-EVENT", "REQ-RETRY"]}], [{"component_id": "COMP-STOCK", "name": "StockTable", "type": "table", "props": ["items"], "state": ["loading", "error"], "requirement_refs": ["REQ-STOCK"]}, {"component_id": "COMP-ADJUST", "name": "AdjustmentForm", "type": "form", "props": ["onSubmit"], "state": ["eventId", "delta", "submitting"], "requirement_refs": ["REQ-EVENT", "REQ-RETRY"]}], [{"binding_id": "BIND-STOCK", "method": "GET", "path": "/api/inventory/items/{itemId}", "consumer": "StockTable", "backend_api_id": "API-STOCK-GET", "requirement_refs": ["REQ-STOCK"]}, {"binding_id": "BIND-EVENT", "method": "POST", "path": "/api/inventory/events", "consumer": "AdjustmentForm", "backend_api_id": "API-STOCK-EVENT", "requirement_refs": ["REQ-EVENT", "REQ-RETRY"]}])
    return SoftwareDomainFixture("inventory_management", "Inventory management", prd, architecture, backend, frontend)


def _leave() -> SoftwareDomainFixture:
    features = [{"requirement_id": "REQ-LEAVE", "name": "Leave request", "description": "Employees submit a bounded leave request.", "priority": "MUST"}, {"requirement_id": "REQ-APPROVAL", "name": "Manager approval", "description": "A manager approves or rejects a leave request.", "priority": "MUST"}, {"requirement_id": "REQ-HISTORY", "name": "Approval history", "description": "The employee can view the approval decision and reason.", "priority": "SHOULD"}]
    prd = _prd("Employee Leave Approval", ["employee", "manager", "hr"], features, [_story("STORY-LEAVE", "employee", "request leave", "plan an absence", ["REQ-LEAVE"], [("AC-LEAVE-DATES", "The request stores start and end dates."), ("AC-LEAVE-PENDING", "A new request starts in PENDING status.")]), _story("STORY-APPROVAL", "manager", "approve a request", "coordinate team availability", ["REQ-APPROVAL", "REQ-HISTORY"], [("AC-APPROVAL-ROLE", "Only a manager can approve or reject a direct report's request."), ("AC-APPROVAL-REASON", "A rejection stores a visible reason.")])], ["Payroll calculation is outside this fixture."], ["Approval must enforce the manager relationship, not only a role string."])
    backend = _backend([{"table_id": "TABLE-LEAVE", "name": "leave_request", "description": "Employee leave request.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "employee_id", "type": "BIGINT", "nullable": False, "description": "Requesting employee."}, {"name": "start_date", "type": "DATE", "nullable": False, "description": "First leave date."}, {"name": "end_date", "type": "DATE", "nullable": False, "description": "Last leave date."}, {"name": "status", "type": "VARCHAR(32)", "nullable": False, "description": "PENDING, APPROVED, or REJECTED."}], "requirement_refs": ["REQ-LEAVE", "REQ-APPROVAL", "REQ-HISTORY"]}, {"table_id": "TABLE-APPROVAL", "name": "leave_approval", "description": "Business approval decision.", "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."}, {"name": "leave_request_id", "type": "BIGINT", "nullable": False, "description": "Leave request."}, {"name": "approver_id", "type": "BIGINT", "nullable": False, "description": "Manager deciding the request."}, {"name": "decision", "type": "VARCHAR(32)", "nullable": False, "description": "APPROVED or REJECTED."}, {"name": "reason", "type": "VARCHAR(512)", "nullable": True, "description": "Decision reason."}], "requirement_refs": ["REQ-APPROVAL", "REQ-HISTORY"]}], [{"api_id": "API-LEAVE-CREATE", "method": "POST", "path": "/api/leave/requests", "description": "Submit an employee leave request.", "request_params": [{"name": "startDate", "type": "string", "required": True, "description": "Start date."}, {"name": "endDate", "type": "string", "required": True, "description": "End date."}], "response_fields": [{"name": "requestId", "type": "number", "description": "Created request."}], "auth_required": True, "required_roles": ["EMPLOYEE"], "requirement_refs": ["REQ-LEAVE"]}, {"api_id": "API-LEAVE-APPROVE", "method": "POST", "path": "/api/leave/requests/{requestId}/approval", "description": "Approve or reject a leave request.", "request_params": [{"name": "requestId", "type": "number", "required": True, "description": "Leave request."}, {"name": "decision", "type": "string", "required": True, "description": "APPROVED or REJECTED."}, {"name": "reason", "type": "string", "required": False, "description": "Decision reason."}], "response_fields": [{"name": "status", "type": "string", "description": "Updated request status."}], "auth_required": True, "required_roles": ["MANAGER"], "requirement_refs": ["REQ-APPROVAL", "REQ-HISTORY"]}])
    architecture = _architecture("Leave approval", [{"module_id": "MOD-LEAVE", "name": "leave", "responsibility": "Own requests and manager approval history.", "depends_on": [], "requirement_refs": ["REQ-LEAVE", "REQ-APPROVAL", "REQ-HISTORY"]}], [{"decision_id": "ADR-APPROVAL", "title": "Approval is a business record", "context": "Leave decisions must be auditable.", "decision": "Persist one approval record for each decision.", "consequences": ["The employee can inspect the decision reason."], "requirement_refs": ["REQ-APPROVAL", "REQ-HISTORY"]}], ["A manager relationship check is required before changing request status."])
    frontend = _frontend([{"route_id": "ROUTE-LEAVE", "path": "/leave", "page": "LeavePage", "requirement_refs": ["REQ-LEAVE", "REQ-APPROVAL", "REQ-HISTORY"]}], [{"page_id": "PAGE-LEAVE", "name": "LeavePage", "purpose": "Submit and review leave approvals.", "components": ["LeaveForm", "ApprovalHistory"], "requirement_refs": ["REQ-LEAVE", "REQ-APPROVAL", "REQ-HISTORY"]}], [{"component_id": "COMP-LEAVE-FORM", "name": "LeaveForm", "type": "form", "props": ["onSubmit"], "state": ["startDate", "endDate", "error"], "requirement_refs": ["REQ-LEAVE"]}, {"component_id": "COMP-APPROVAL", "name": "ApprovalHistory", "type": "timeline", "props": ["requestId", "decision"], "state": ["loading", "reason"], "requirement_refs": ["REQ-APPROVAL", "REQ-HISTORY"]}], [{"binding_id": "BIND-LEAVE", "method": "POST", "path": "/api/leave/requests", "consumer": "LeaveForm", "backend_api_id": "API-LEAVE-CREATE", "requirement_refs": ["REQ-LEAVE"]}, {"binding_id": "BIND-APPROVAL", "method": "POST", "path": "/api/leave/requests/{requestId}/approval", "consumer": "ApprovalHistory", "backend_api_id": "API-LEAVE-APPROVE", "requirement_refs": ["REQ-APPROVAL", "REQ-HISTORY"]}])
    return SoftwareDomainFixture("employee_leave_approval", "Employee leave approval", prd, architecture, backend, frontend)


_FIXTURES = {
    "campus_marketplace": _marketplace(),
    "inventory_management": _inventory(),
    "employee_leave_approval": _leave(),
}
