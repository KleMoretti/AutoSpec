import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
    apiGet,
    apiPost,
    envInteger,
    jsonBody,
    requiredPositiveIntegerEnvironment,
    requireOptIn,
    setupSession,
    uniqueIdempotencyKey,
} from './lib/client.js';

const workflowConcurrency = envInteger('AUTOSPEC_WORKFLOW_CONCURRENCY', 1, 1, 1000);
const completionDuration = new Trend('workflow_completion_duration', true);
const terminalFailure = new Rate('workflow_terminal_failure');
const workflowTimeout = new Rate('workflow_timeout');
const terminalStatuses = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

export const options = {
    scenarios: {
        workflow_baseline: {
            executor: 'per-vu-iterations',
            vus: workflowConcurrency,
            iterations: 1,
            maxDuration: __ENV.AUTOSPEC_WORKFLOW_MAX_DURATION || '12m',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
        'http_req_failed{endpoint:workflow_start}': ['rate<0.01'],
        'http_req_duration{endpoint:workflow_start}': ['p(95)<800', 'p(99)<2000'],
        'http_req_failed{endpoint:workflow_status}': ['rate<0.01'],
        'http_req_duration{endpoint:workflow_status}': ['p(95)<300', 'p(99)<800'],
        workflow_completion_duration: ['p(95)<300000', 'p(99)<600000'],
        workflow_terminal_failure: ['rate<0.01'],
        workflow_timeout: ['rate<0.01'],
    },
};

export function setup() {
    requireOptIn('AUTOSPEC_ALLOW_WORKFLOW_LOAD');
    const session = setupSession();
    session.projectId = requiredPositiveIntegerEnvironment('AUTOSPEC_PROJECT_ID');
    session.workflowVersionId = requiredPositiveIntegerEnvironment('AUTOSPEC_WORKFLOW_VERSION_ID');
    session.workflowInput = workflowInput();
    return session;
}

export default function (session) {
    const startedAt = Date.now();
    const idempotencyKey = uniqueIdempotencyKey(session, 'workflow');
    const response = apiPost(
        session,
        '/api/workflow-runs',
        {
            projectId: session.projectId,
            workflowVersionId: session.workflowVersionId,
            input: session.workflowInput,
            idempotencyKey,
        },
        'workflow_start',
        'POST /api/workflow-runs',
    );

    if (response.status !== 200) {
        terminalFailure.add(true);
        workflowTimeout.add(false);
        return;
    }

    let run = jsonBody(response, 'workflow start response');
    if (!Number.isInteger(run.id)) {
        check(run, { 'workflow start returned an id': () => false });
        terminalFailure.add(true);
        workflowTimeout.add(false);
        return;
    }

    const timeoutMilliseconds = envInteger(
        'AUTOSPEC_WORKFLOW_TIMEOUT_SECONDS',
        600,
        1,
        7200,
    ) * 1000;
    const pollMilliseconds = envInteger(
        'AUTOSPEC_WORKFLOW_POLL_MS',
        2000,
        100,
        60000,
    );

    while (Date.now() - startedAt < timeoutMilliseconds) {
        if (terminalStatuses.has(run.status)) {
            recordTerminal(run.status, Date.now() - startedAt);
            return;
        }

        sleep(pollMilliseconds / 1000);
        const statusResponse = apiGet(
            session,
            `/api/workflow-runs/${run.id}`,
            'workflow_status',
            'GET /api/workflow-runs/{runId}',
        );
        if (statusResponse.status !== 200) {
            terminalFailure.add(true);
            workflowTimeout.add(false);
            return;
        }
        run = jsonBody(statusResponse, 'workflow status response');
    }

    workflowTimeout.add(true);
    terminalFailure.add(true);
    check(run, { 'workflow reached a terminal state before timeout': () => false });
}

function recordTerminal(status, elapsedMilliseconds) {
    completionDuration.add(elapsedMilliseconds, { terminal_status: status });
    workflowTimeout.add(false);
    terminalFailure.add(status !== 'COMPLETED');
    check(status, {
        'workflow completed successfully': (candidate) => candidate === 'COMPLETED',
    });
}

function workflowInput() {
    const raw = __ENV.AUTOSPEC_WORKFLOW_INPUT_JSON
        || '{"requirement":"Synthetic k6 workflow baseline input"}';
    try {
        const parsed = JSON.parse(raw);
        if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
            throw new Error('value must be a JSON object');
        }
        return parsed;
    } catch (error) {
        throw new Error(`AUTOSPEC_WORKFLOW_INPUT_JSON is invalid: ${error.message}`);
    }
}
