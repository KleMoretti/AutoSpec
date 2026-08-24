import { check, sleep } from 'k6';
import {
    apiGet,
    envInteger,
    jsonBody,
    setupSession,
} from './lib/client.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate>0.99'],
        'http_req_failed{endpoint:health}': ['rate<0.01'],
        'http_req_duration{endpoint:health}': ['p(95)<500', 'p(99)<1000'],
        'http_req_failed{endpoint:project_list}': ['rate<0.01'],
        'http_req_duration{endpoint:project_list}': ['p(95)<500', 'p(99)<1000'],
    },
};

export function setup() {
    return setupSession();
}

export default function (session) {
    const limit = envInteger('AUTOSPEC_PAGE_LIMIT', 10, 1, 100);
    const health = apiGet(session, '/api/health', 'health', 'GET /api/health');
    if (health.status === 200) {
        check(jsonBody(health, 'health response'), {
            'health status is UP': (payload) => payload.status === 'UP',
        });
    }

    const projects = apiGet(
        session,
        `/api/projects?limit=${limit}&offset=0`,
        'project_list',
        'GET /api/projects',
    );
    if (projects.status === 200) {
        check(jsonBody(projects, 'project list response'), {
            'project list is an array': (payload) => Array.isArray(payload),
        });
    }

    sleep(0.1);
}
