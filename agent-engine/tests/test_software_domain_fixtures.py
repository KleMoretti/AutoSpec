import pytest

from fixtures.software_domains import SUPPORTED_DOMAIN_KEYS, get_fixture
from review.shared_contract import validate_backend_contract, validate_frontend_contract


@pytest.mark.parametrize("key", SUPPORTED_DOMAIN_KEYS)
def test_domain_fixture_keeps_stable_ids_across_all_artifacts(key: str) -> None:
    fixture = get_fixture(key)
    architecture = fixture.shared_architecture()
    requirement_ids = {
        feature.requirement_id for feature in fixture.prd.core_features
    }

    assert architecture.shared_contract is not None
    validate_backend_contract(architecture, fixture.backend)
    validate_frontend_contract(architecture, fixture.frontend)

    for artifact in (fixture.backend, fixture.frontend):
        refs = {
            ref
            for item in artifact.model_dump(mode="json").values()
            for ref in _requirement_refs(item)
        }
        assert refs <= requirement_ids


def _requirement_refs(value: object) -> set[str]:
    if isinstance(value, dict):
        direct = set(value.get("requirement_refs", []))
        return direct | set().union(
            *(_requirement_refs(child) for child in value.values())
        )
    if isinstance(value, list):
        return set().union(*(_requirement_refs(child) for child in value))
    return set()
