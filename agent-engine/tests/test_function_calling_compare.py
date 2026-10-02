from evaluation.function_calling_compare import build_comparison


def test_function_calling_protocols_share_allowlist_and_rejection_semantics() -> None:
    report = build_comparison()

    assert report["execution_mode"] == "FIXTURE_PROTOCOL_ADAPTER"
    assert report["provider_quality"] == "NOT_MEASURED"
    assert report["paired_differences"] == []
    for protocol in ("JSON_IN_PROMPT", "NATIVE_TOOL_CALL"):
        assert report["protocols"][protocol]["case_count"] == 5
        assert report["protocols"][protocol]["passed"] == 3
        assert report["protocols"][protocol]["rejected"] == 2
        assert report["protocols"][protocol]["unexpected_accepts"] == 0
