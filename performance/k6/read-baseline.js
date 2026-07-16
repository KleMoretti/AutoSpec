import { sleep } from 'k6';
import {
    apiGet,
    envInteger,
    requiredPositiveIntegerEnvironment,
    setupSession,
} from './lib/client.js';

const readEndpoints = [
    'project_list',
    'project_detail',
    'workflow_history',
    'artifact_history',
    'event_history',
    'model_invocation_history',
    'project_diagnostics',
];

const thresholds = {
    checks: ['rate>0.99'],
};

for (const endpoint of readEndpoints) {
    thresholds[`http_req_failed{endpoint:${endpoint}}`] = ['rate<0.01'];
    thresholds[`http_req_duration{endpoint:${endpoint}}`] = ['p(95)<300', 'p(99)<800'];
}

export const options = {
    scenarios: {
        read_baseline: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: __ENV.AUTOSPEC_RAMP_UP || '30s', target: envInteger('AUTOSPEC_READ_VUS', 10, 1, 2000) },
                { duration: __ENV.AUTOSPEC_DURATION || '2m', target: envInteger('AUTOSPEC_READ_VUS', 10, 1, 2000) },
                { duration: __ENV.AUTOSPEC_RAMP_DOWN || '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds,
};

export function setup() {
    const session = setupSession();
    session.projectId = requiredPositiveIntegerEnvironment('AUTOSPEC_PROJECT_ID');
    const workflowRunId = (__ENV.AUTOSPEC_WORKFLOW_RUN_ID || '').trim();
    if (workflowRunId !== '') {
        if (!/^[1-9][0-9]*$/.test(workflowRunId) || !Number.isSafeInteger(Number(workflowRunId))) {
            throw new Error('AUTOSPEC_WORKFLOW_RUN_ID must be a positive safe integer');
        }
        session.workflowRunId = Number(workflowRunId);
    } else {
        session.workflowRunId = '';
    }
    return session;
}

export default function (session) {
    const limit = envInteger('AUTOSPEC_PAGE_LIMIT', 50, 1, 100);
    const listOffset = envInteger('AUTOSPEC_PROJECT_OFFSET', 0, 0, 1000000000);
    const historyOffset = envInteger('AUTOSPEC_HISTORY_OFFSET', 0, 0, 1000000000);
    const pauseMilliseconds = envInteger('AUTOSPEC_READ_PAUSE_MS', 250, 0, 60000);
    const projectPath = `/api/projects/${encodeURIComponent(session.projectId)}`;

    apiGet(
        session,
        `/api/projects?limit=${limit}&offset=${listOffset}`,
        'project_list',
        'GET /api/projects',
    );
    apiGet(session, projectPath, 'project_detail', 'GET /api/projects/{projectId}');
    apiGet(
        session,
        `${projectPath}/workflow-runs?limit=${limit}&offset=${historyOffset}`,
        'workflow_history',
        'GET /api/projects/{projectId}/workflow-runs',
    );
    apiGet(
        session,
        `${projectPath}/artifacts?limit=${limit}&offset=${historyOffset}`,
        'artifact_history',
        'GET /api/projects/{projectId}/artifacts',
    );
    apiGet(
        session,
        `${projectPath}/events/history?limit=${limit}&offset=${historyOffset}`,
        'event_history',
        'GET /api/projects/{projectId}/events/history',
    );
    apiGet(
        session,
        `${projectPath}/model-invocations?limit=${limit}&offset=${historyOffset}`,
        'model_invocation_history',
        'GET /api/projects/{projectId}/model-invocations',
    );
    apiGet(
        session,
        `${projectPath}/diagnostics`,
        'project_diagnostics',
        'GET /api/projects/{projectId}/diagnostics',
    );

    if (session.workflowRunId !== '') {
        const runId = encodeURIComponent(session.workflowRunId);
        apiGet(
            session,
            `/api/workflow-runs/${runId}`,
            'workflow_run_detail',
            'GET /api/workflow-runs/{runId}',
        );
        apiGet(
            session,
            `/api/workflow-runs/${runId}/nodes`,
            'workflow_node_history',
            'GET /api/workflow-runs/{runId}/nodes',
        );
    }

    if (pauseMilliseconds > 0) {
        sleep(pauseMilliseconds / 1000);
    }
}
