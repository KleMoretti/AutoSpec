import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from evaluation.ablation import ablation_configs, evaluate_release_gate
from evaluation.autospec_case_catalog import list_autospec_cases
from evaluation.control_plane import CollectionConfig, ControlPlaneCollector, digest, measure_case
from evaluation.budget_ledger import BudgetLedger
from evaluation.run_control_plane import select_cases


@pytest.fixture
def eval_output() -> Path:
    # Python 3.13's Windows mode=0700 temporary folders deny access to the
    # restricted test process. Normal inherited ACLs work in the build directory.
    path = Path(__file__).resolve().parents[1] / "target" / "eval-tests" / uuid4().hex
    path.mkdir(parents=True)
    return path


def config() -> CollectionConfig:
    return CollectionConfig(experiment_id="test", version_ids={"A": 1}, code_version="test",
                            environment={"name": "test"}, run_max_cost=1, total_max_cost=1,
                            run_timeout_seconds=1, poll_seconds=.001)


@pytest.mark.asyncio
async def test_collector_uses_published_formal_api_and_keeps_fixture_and_budget_unmeasured(eval_output: Path) -> None:
    spec = json.loads((Path(__file__).resolve().parents[1] / "contracts" /
                      "autospec-v5-agent-execution-v3-a.workflow.json").read_text())
    created = []
    def handler(request):
        path = request.url.path
        if path == "/api/workflows/autospec-v5/versions":
            return httpx.Response(200, json=[{"id": 1, "status": "PUBLISHED", "specJson": json.dumps(spec), "contentHash": digest(spec)}])
        if path == "/api/projects":
            created.append(json.loads(request.content))
            return httpx.Response(200, json={"projectId": 7})
        if path == "/api/workflow-runs":
            body = json.loads(request.content)
            assert set(body["input"]) == {"requirement"}  # no reference answer leakage
            assert body["executionPolicy"]["maxCost"] == 1
            return httpx.Response(200, json={"id": 9, "status": "FAILED"})
        if path.endswith("/trace"):
            return httpx.Response(200, json={"workflowRunId": 9, "correlationId": "trace-9", "executionBundleHash": "a" * 64,
                "nodes": [{"nodeRunId": 10, "invocations": [{"callType": "MODEL", "providerKey": "local",
                "modelName": "fixture", "status": "SUCCEEDED", "inputTokens": 3, "outputTokens": 2, "cacheTokens": 0}]}]})
        if path.endswith("/nodes"):
            return httpx.Response(200, json=[{"id": 10}])
        if path.endswith("/artifacts"):
            return httpx.Response(200, json=[])
        raise AssertionError(path)

    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        collector = ControlPlaneCollector(client, config(), eval_output)
        result = await collector("A", list_autospec_cases()[:2], ablation_configs()[0])
    assert result.execution_mode == "FIXTURE_BASELINE"
    assert result.status == "PARTIAL"
    assert result.case_results[1].failure_codes == ["EXPERIMENT_BUDGET_EXHAUSTED"]
    assert result.case_results[0].cost is None
    assert len(created) == 1
    assert (eval_output / "A-results.json").exists()


@pytest.mark.asyncio
async def test_draft_preflight_does_not_create_projects(eval_output: Path) -> None:
    requests = []
    def handler(request):
        requests.append(request.method)
        return httpx.Response(200, json=[{"id": 1, "status": "DRAFT"}])
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        collector = ControlPlaneCollector(client, config(), eval_output)
        with pytest.raises(ValueError, match="published"):
            await collector.preflight()
    assert requests == ["GET"]


def test_unknown_prices_are_not_zero_cost_and_failed_cases_keep_token_usage() -> None:
    trace = {"correlationId": "id", "executionBundleHash": "b" * 64, "nodes": [{"invocations": [
        {"callType": "MODEL", "providerKey": "provider", "modelName": "model", "inputTokens": 100,
         "outputTokens": 20, "cacheTokens": 10, "status": "FAILED", "errorCode": "MODEL_TIMEOUT"}
    ]}]}
    result = measure_case(list_autospec_cases()[0], 1, {"id": 1, "status": "FAILED"}, trace, [], {}, 10)
    assert result.tokens == 120
    assert result.cost is None
    assert result.gate_pass is False
    assert result.must_trace_coverage is None


@pytest.mark.parametrize("error_code", ["VALIDATION_ERROR", "OUTPUT_SCHEMA_ERROR"])
def test_runtime_schema_failure_is_not_reported_as_valid_schema(error_code: str) -> None:
    result = measure_case(list_autospec_cases()[0], 1, {"id": 1, "status": "FAILED"},
                          {"nodes": [{"errorCode": error_code, "invocations": [], "steps": []}]}, [], {}, 1)
    assert result.schema_invalid_count == 1
    assert result.failure_codes == [error_code]


def live_config() -> CollectionConfig:
    source = Path(__file__).resolve().parents[2] / "docs/archive/examples/agent-eval-live-cny10.json"
    value = CollectionConfig.model_validate_json(source.read_text(encoding="utf-8"))
    return value.model_copy(update={"version_ids": {"A": 1}})


def test_authorization_survives_restart_and_serializes_concurrent_reservations(eval_output: Path) -> None:
    path = eval_output / "budget.sqlite3"
    BudgetLedger(path, "approved-round", "CNY", 10)
    def reserve(index):
        return BudgetLedger(path, "approved-round", "CNY", 10).reserve(f"experiment-{index}", 2)
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(8))) == 5
    assert not BudgetLedger(path, "approved-round", "CNY", 10).reserve("after-restart", 2)
    with pytest.raises(ValueError, match="cannot be changed"):
        BudgetLedger(path, "approved-round", "CNY", 20)
    with pytest.raises(ValueError, match="cannot be changed"):
        BudgetLedger(path, "approved-round", "USD", 10)


@pytest.mark.asyncio
async def test_interrupted_live_request_keeps_reservation_across_experiment_ids(eval_output: Path) -> None:
    cfg = live_config()
    spec = json.loads((Path(__file__).resolve().parents[1] / "contracts" /
                       "autospec-v5-agent-execution-v4-a.workflow.json").read_text())
    posts = []
    def handler(request):
        if request.method == "GET":
            return httpx.Response(200, json=[{"id": 1, "status": "PUBLISHED", "specJson": json.dumps(spec), "contentHash": digest(spec)}])
        posts.append(request.url.path)
        raise httpx.ReadTimeout("unknown request outcome", request=request)
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        for index in range(6):
            collector = ControlPlaneCollector(client, cfg.model_copy(update={"experiment_id": f"restart-{index}"}),
                                               eval_output / str(index), budget_ledger_path=eval_output / "budget.sqlite3")
            if index < 5:
                with pytest.raises(httpx.ReadTimeout):
                    await collector("A", select_cases(cfg), ablation_configs()[0])
            else:
                result = await collector("A", select_cases(cfg), ablation_configs()[0])
                assert result.case_results[0].failure_codes == ["EXPERIMENT_BUDGET_EXHAUSTED"]
    assert len(posts) == 5


@pytest.mark.asyncio
async def test_live_preflight_rejects_unpriced_historical_contract_before_writes(eval_output: Path) -> None:
    cfg = live_config().model_copy(update={"contract_family": "v3"})
    spec = json.loads((Path(__file__).resolve().parents[1] / "contracts" /
                       "autospec-v5-agent-execution-v3-a.workflow.json").read_text())
    def handler(request):
        assert request.method == "GET"
        return httpx.Response(200, json=[{"id": 1, "status": "PUBLISHED", "specJson": json.dumps(spec), "contentHash": digest(spec)}])
    async with httpx.AsyncClient(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        collector = ControlPlaneCollector(client, cfg, eval_output, budget_ledger_path=eval_output / "budget.sqlite3")
        with pytest.raises(ValueError, match="pin a provider/model"):
            await collector.preflight()
        assert not collector.versions
        assert not (eval_output / "budget.sqlite3").exists()


def test_live_smoke_selects_the_same_single_case_for_all_groups() -> None:
    cfg = live_config()
    assert [case.case_id for case in select_cases(cfg)] == ["crud_inventory"]
    assert cfg.max_runs == 4
    assert cfg.run_max_cost * cfg.max_runs == 8 <= cfg.total_max_cost == 10
    with pytest.raises(ValueError, match="unique IDs"):
        select_cases(cfg.model_copy(update={"case_ids": ["not-in-the-dataset"]}))
