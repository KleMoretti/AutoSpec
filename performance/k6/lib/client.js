import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const DEFAULT_BASE_URL = 'http://localhost:8080';
const DEFAULT_USERNAME = 'owner';

let requestSequence = 0;

export const config = Object.freeze({
    baseUrl: trimTrailingSlash(__ENV.AUTOSPEC_BASE_URL || DEFAULT_BASE_URL),
    requestTimeout: __ENV.AUTOSPEC_REQUEST_TIMEOUT || '30s',
});

export function envBoolean(name, fallback = false) {
    const raw = __ENV[name];
    if (raw === undefined || raw === '') {
        return fallback;
    }
    if (/^(true|1|yes)$/i.test(raw)) {
        return true;
    }
    if (/^(false|0|no)$/i.test(raw)) {
        return false;
    }
    throw new Error(`${name} must be true or false`);
}

export function envInteger(name, fallback, minimum, maximum) {
    const raw = __ENV[name];
    const value = raw === undefined || raw === '' ? fallback : Number(raw);
    if (!Number.isInteger(value) || value < minimum || value > maximum) {
        throw new Error(`${name} must be an integer between ${minimum} and ${maximum}`);
    }
    return value;
}

export function requiredEnvironment(name) {
    const value = __ENV[name];
    if (value === undefined || String(value).trim() === '') {
        fail(`${name} is required`);
    }
    return String(value).trim();
}

export function requiredPositiveIntegerEnvironment(name) {
    const raw = requiredEnvironment(name);
    if (!/^[1-9][0-9]*$/.test(raw)) {
        fail(`${name} must be a positive integer`);
    }
    const value = Number(raw);
    if (!Number.isSafeInteger(value)) {
        fail(`${name} exceeds JavaScript's safe integer range`);
    }
    return value;
}

export function requireOptIn(name) {
    if (!envBoolean(name, false)) {
        fail(`${name}=true is required because this scenario changes server state`);
    }
}

export function setupSession() {
    const suppliedToken = (__ENV.AUTOSPEC_SESSION_TOKEN || '').trim();
    const testRunId = (__ENV.AUTOSPEC_TEST_RUN_ID || generatedTestRunId()).trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/.test(testRunId)) {
        fail('AUTOSPEC_TEST_RUN_ID must be 1-32 characters using letters, digits, underscore, or hyphen');
    }
    if (suppliedToken !== '') {
        return { token: suppliedToken, testRunId };
    }

    const response = request('POST', '/api/auth/login', {
        body: {
            username: __ENV.AUTOSPEC_USERNAME || DEFAULT_USERNAME,
            password: requiredEnvironment('AUTOSPEC_PASSWORD'),
        },
        endpoint: 'login',
        name: 'POST /api/auth/login',
        expectedStatuses: [200],
    });
    if (response.status !== 200) {
        fail(`login failed with status ${response.status}`);
    }

    const payload = jsonBody(response, 'login response');
    if (!payload.sessionToken) {
        fail('login response did not contain sessionToken');
    }
    return { token: payload.sessionToken, testRunId };
}

export function apiGet(session, path, endpoint, name) {
    return request('GET', path, {
        token: session.token,
        endpoint,
        name,
        expectedStatuses: [200],
    });
}

export function apiPost(session, path, body, endpoint, name, expectedStatuses = [200]) {
    return request('POST', path, {
        token: session.token,
        body,
        endpoint,
        name,
        expectedStatuses,
    });
}

export function request(method, path, options = {}) {
    const headers = Object.assign(
        { Accept: 'application/json' },
        options.body === undefined ? {} : { 'Content-Type': 'application/json' },
        options.token ? { 'X-AutoSpec-Session-Token': options.token } : {},
        options.headers || {},
    );
    const endpoint = options.endpoint || 'unclassified';
    const expectedStatuses = options.expectedStatuses || [200];
    const payload = options.body === undefined ? null : JSON.stringify(options.body);
    const response = http.request(method, `${config.baseUrl}${path}`, payload, {
        headers,
        tags: Object.assign(
            {
                endpoint,
                name: options.name || `${method} ${path}`,
            },
            options.tags || {},
        ),
        timeout: options.timeout || config.requestTimeout,
    });

    check(response, {
        [`${endpoint}: expected HTTP status`]: (candidate) => expectedStatuses.includes(candidate.status),
    });
    return response;
}

export function jsonBody(response, label) {
    try {
        return response.json();
    } catch (error) {
        fail(`${label} was not valid JSON: ${error.message}`);
    }
}

export function uniqueIdempotencyKey(session, prefix = 'autospec-k6') {
    requestSequence += 1;
    const scenarioName = safeSegment(exec.scenario.name, 20);
    const runId = safeSegment(session.testRunId, 32);
    const key = [
        safeSegment(prefix, 16),
        runId,
        scenarioName,
        exec.vu.idInTest,
        exec.scenario.iterationInTest,
        requestSequence,
    ].join('-');
    // With the bounded text segments and JavaScript-safe integer components,
    // this remains below the API's 128-character limit without dropping the
    // per-request sequence that guarantees uniqueness within a test run.
    return key.slice(0, 128);
}

function generatedTestRunId() {
    return `k6-${Date.now()}-${Math.floor(Math.random() * 1000000000)}`;
}

function safeSegment(value, maximumLength) {
    const safe = String(value)
        .replace(/[^A-Za-z0-9_-]/g, '-')
        .replace(/-+/g, '-')
        .replace(/^-|-$/g, '');
    return (safe || 'unknown').slice(0, maximumLength);
}

function trimTrailingSlash(value) {
    return value.replace(/\/+$/, '');
}
