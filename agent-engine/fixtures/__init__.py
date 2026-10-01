"""Deterministic, provider-neutral fixtures used by offline AutoSpec runs."""

from fixtures.software_domains import (
    SUPPORTED_DOMAIN_KEYS,
    SoftwareDomainFixture,
    fixture_for_requirement,
    get_fixture,
    fixture_key_for_requirement,
)

__all__ = [
    "SUPPORTED_DOMAIN_KEYS",
    "SoftwareDomainFixture",
    "fixture_for_requirement",
    "get_fixture",
    "fixture_key_for_requirement",
]
