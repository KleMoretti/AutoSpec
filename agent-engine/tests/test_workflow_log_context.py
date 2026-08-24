import asyncio
import logging
from io import StringIO
from types import SimpleNamespace

import pytest

from runtime.workflow_log_context import (
    WorkflowLogContextFilter,
    bind_workflow_log_context,
    workflow_log_context,
)


TRACEPARENT = (
    "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
)


def command():
    return SimpleNamespace(
        correlation_id="correlation-7",
        traceparent=TRACEPARENT,
        workflow_run_id=7,
        node_run_id=11,
        execution_id="7:fixture:1:1",
    )


def test_filter_formats_bound_fields_and_clears_them_afterward():
    output = StringIO()
    handler = logging.StreamHandler(output)
    handler.addFilter(WorkflowLogContextFilter())
    handler.setFormatter(
        logging.Formatter(
            "%(traceId)s %(correlationId)s %(workflowRunId)s "
            "%(nodeRunId)s %(executionId)s %(message)s"
        )
    )
    logger = logging.getLogger("test.workflow.context")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    logger.addHandler(handler)
    try:
        with bind_workflow_log_context(command()):
            logger.info("inside")
        logger.info("outside")
    finally:
        logger.removeHandler(handler)

    lines = output.getvalue().splitlines()
    assert lines[0] == (
        "0123456789abcdef0123456789abcdef correlation-7 7 11 "
        "7:fixture:1:1 inside"
    )
    assert lines[1] == "- - - - - outside"


@pytest.mark.asyncio
async def test_context_is_inherited_by_async_tasks_and_restored():
    async def read_context():
        await asyncio.sleep(0)
        return workflow_log_context()

    with bind_workflow_log_context(command()):
        inherited = await asyncio.create_task(read_context())

    assert inherited == {
        "traceId": "0123456789abcdef0123456789abcdef",
        "correlationId": "correlation-7",
        "workflowRunId": "7",
        "nodeRunId": "11",
        "executionId": "7:fixture:1:1",
    }
    assert workflow_log_context() == {}
