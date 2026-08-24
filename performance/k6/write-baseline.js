import { check, sleep } from 'k6';
import {
    apiPost,
    envInteger,
    jsonBody,
    requireOptIn,
    setupSession,
    uniqueIdempotencyKey,
} from './lib/client.js';

const writeVus = envInteger('AUTOSPEC_WRITE_VUS', 5, 1, 500);

export const options = {
    scenarios: {
        project_writes: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: __ENV.AUTOSPEC_RAMP_UP || '15s', target: writeVus },
                { duration: __ENV.AUTOSPEC_DURATION || '1m', target: writeVus },
                { duration: __ENV.AUTOSPEC_RAMP_DOWN || '15s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
        'http_req_failed{endpoint:project_create}': ['rate<0.01'],
        'http_req_duration{endpoint:project_create}': ['p(95)<500', 'p(99)<1200'],
    },
};

export function setup() {
    requireOptIn('AUTOSPEC_ALLOW_WRITES');
    return setupSession();
}

export default function (session) {
    const key = uniqueIdempotencyKey(session, 'project');
    const response = apiPost(
        session,
        '/api/projects',
        {
            name: `perf-write-${key}`.slice(0, 128),
            requirement: `Synthetic performance fixture. testRunId=${session.testRunId}; key=${key}`,
        },
        'project_create',
        'POST /api/projects',
    );

    if (response.status === 200) {
        check(jsonBody(response, 'project create response'), {
            'created project has an id': (payload) => Number.isInteger(payload.projectId),
            'created project reports a status': (payload) => typeof payload.status === 'string',
        });
    }

    const pauseMilliseconds = envInteger('AUTOSPEC_WRITE_PAUSE_MS', 250, 0, 60000);
    if (pauseMilliseconds > 0) {
        sleep(pauseMilliseconds / 1000);
    }
}
