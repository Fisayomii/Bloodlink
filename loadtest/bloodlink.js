// Load test: response times at the 50th, 95th and 99th percentiles, step by step, and the step where the service stops
// meeting its target (docs/performance.md).
//
//   k6 run -e API_URL=https://bloodlink-o2ie.onrender.com loadtest/bloodlink.js
//
// Normally run from GitHub Actions (workflow "load-test", started by hand), so every run measures from the same place.
// Options, all optional: RATES (requests a second for each step, default 1,2,3,4,6,8,10,13,16,20), STEP_SECONDS (45),
// TARGET_P95_MS (500), TARGET_ERROR_PCT (1).
//
// Each step sends requests at a fixed rate whatever the service does (an "open" model): if the service slows down,
// requests queue up instead of the test politely waiting, which is how real users behave. Every step is its own k6
// scenario, so each one gets its own percentiles.
//
// It only reads, plus sign-ins: it must not fill the live demo data with thousands of requests and bookings. Each
// request carries X-Request-Id "load-<run>-<n>", so the service's logs show which lines came from the test.

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE = (__ENV.API_URL || '').replace(/\/+$/, '');
const RATES = (__ENV.RATES || '1,2,3,4,6,8,10,13,16,20').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 45);
const WARM_UP_SECONDS = 30;
const TARGET_P95_MS = Number(__ENV.TARGET_P95_MS || 500);
const TARGET_ERROR_PCT = Number(__ENV.TARGET_ERROR_PCT || 1);
const RUN = __ENV.RUN_ID || `${Date.now()}`;
const PASSWORD = 'Demo-Password-1';

if (!BASE) throw new Error('Set API_URL, for example -e API_URL=https://bloodlink-o2ie.onrender.com');

// A request the service answered with something other than what the journey expects (any status but the expected
// one, or no answer within the timeout).
const failed = new Rate('failed');
// A 403 or 429 that did not come from the service (no JSON body in the contract's Error shape): the host's firewall.
const blockedByHost = new Rate('blocked_by_host');

const stepName = (rate) => `step_${String(rate).padStart(3, '0')}_per_s`;

function steps() {
  const scenarios = {
    warm_up: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s', duration: `${WARM_UP_SECONDS}s`,
      preAllocatedVUs: 5, maxVUs: 20, gracefulStop: '0s' },
  };
  RATES.forEach((rate, i) => {
    scenarios[stepName(rate)] = {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STEP_SECONDS}s`,
      startTime: `${WARM_UP_SECONDS + i * STEP_SECONDS}s`,
      preAllocatedVUs: Math.max(5, rate * 2), maxVUs: Math.max(20, rate * 12),
      // Requests still running when the step ends finish (and count) in this step, up to the request timeout.
      gracefulStop: '10s',
    };
  });
  return scenarios;
}

// A threshold per step makes k6 keep that step's figures separately; the limits themselves are the target, and they
// do not stop the test (the point is to go past the target).
function thresholds() {
  const t = {};
  for (const name of [...RATES.map(stepName)]) {
    t[`http_req_duration{scenario:${name}}`] = [`p(95)<${TARGET_P95_MS}`];
    t[`failed{scenario:${name}}`] = [`rate<${TARGET_ERROR_PCT / 100}`];
    t[`blocked_by_host{scenario:${name}}`] = ['rate<1'];
    t[`iterations{scenario:${name}}`] = ['count>=0'];
    t[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
  }
  return t;
}

export const options = {
  scenarios: steps(),
  thresholds: thresholds(),
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max', 'count'],
  // A free service that has stopped answering should show up as slow failures, not hang the test.
  httpTimeout: '10s',
  noConnectionReuse: false,
  userAgent: 'bloodlink-load-test',
  setupTimeout: '180s',
};

function headers(token, n) {
  const h = { 'X-Request-Id': `load-${RUN}-${n}`, 'Content-Type': 'application/json' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function signIn(email) {
  const r = http.post(`${BASE}/sessions`, JSON.stringify({ email, password: PASSWORD }),
    { headers: headers(null, `setup-${email}`), timeout: '60s' });
  if (r.status !== 201) throw new Error(`Sign-in failed for ${email}: ${r.status} ${r.body}`);
  return r.json();
}

export function setup() {
  // The free host sleeps after 15 idle minutes; wake it before anything is timed.
  for (let i = 0; i < 18; i++) {
    const r = http.get(`${BASE}/actuator/health`, { timeout: '60s', headers: headers(null, 'wake') });
    if (r.status === 200) break;
  }
  const bank = signIn('staff@lagos-central.bank.demo');
  return {
    bank: bank.accessToken,
    bankId: bank.user.organisation.id,
    hospital: signIn('staff@yaba.hospital.demo').accessToken,
    donor: signIn('donor@bloodlink.demo').accessToken,
  };
}

const GROUPS = ['A', 'B', 'AB', 'O'];
const RHESUS = ['POSITIVE', 'NEGATIVE'];
const COMPONENTS = ['WHOLE_BLOOD', 'RED_CELLS', 'PLATELETS', 'PLASMA', 'CRYOPRECIPITATE'];
const pick = (list) => list[Math.floor(Math.random() * list.length)];

// The mix, by share of requests: what hospitals, banks and donors do most, weighted towards the screens used most.
const MIX = [
  [30, 'availability search (hospital)', (t) => ['GET', `/availability?bloodGroup=${pick(GROUPS)}&rhesus=${pick(RHESUS)}&component=${pick(COMPONENTS)}`, t.hospital, 200]],
  [20, "bank's requests, first page", (t) => ['GET', '/requests?size=20', t.bank, 200]],
  [15, 'dashboard (bank)', (t) => ['GET', '/dashboard', t.bank, 200]],
  [15, 'donation slots, next 14 days', (t) => ['GET', '/donation-slots', t.donor, 200]],
  [10, "bank's units, first page", (t) => ['GET', '/units?size=20', t.bank, 200]],
  [5, "donor's bookings", (t) => ['GET', '/bookings', t.donor, 200]],
  [5, 'sign-in', () => ['POST', '/sessions', null, 201]],
];
const TOTAL_WEIGHT = MIX.reduce((sum, [w]) => sum + w, 0);

let counter = 0;

export default function (tokens) {
  let roll = Math.random() * TOTAL_WEIGHT;
  let entry = MIX[MIX.length - 1];
  for (const m of MIX) {
    if ((roll -= m[0]) < 0) { entry = m; break; }
  }
  const [, name, build] = entry;
  const [method, path, token, expected] = build(tokens);
  const n = `${__VU}-${++counter}`;
  const params = { headers: headers(token, n), tags: { name } };
  const r = method === 'POST'
    ? http.post(`${BASE}${path}`, JSON.stringify({ email: pick(['staff@lekki.bank.demo', 'staff@vi.hospital.demo', 'donor2@bloodlink.demo']), password: PASSWORD }), params)
    : http.get(`${BASE}${path}`, params);

  const ok = r.status === expected;
  failed.add(!ok);
  const fromHost = (r.status === 403 || r.status === 429) && !(r.body || '').includes('"requestId"');
  blockedByHost.add(fromHost);
  check(r, { [`${name}: ${expected}`]: () => ok });
}

// The report: one row per step, and the first step that misses the target.
export function handleSummary(data) {
  const m = data.metrics;
  const value = (key, stat) => (m[key] && m[key].values ? m[key].values[stat] : undefined);
  const ms = (v) => (v === undefined ? '—' : `${Math.round(v)}`);
  const rows = [];
  let saturation = null;
  let lastGood = null;
  for (const rate of RATES) {
    const s = stepName(rate);
    const d = `http_req_duration{scenario:${s}}`;
    const sent = value(`iterations{scenario:${s}}`, 'count') || 0;
    const dropped = value(`dropped_iterations{scenario:${s}}`, 'count') || 0;
    const errorPct = 100 * (value(`failed{scenario:${s}}`, 'rate') || 0);
    const blockedPct = 100 * (value(`blocked_by_host{scenario:${s}}`, 'rate') || 0);
    const p95 = value(d, 'p(95)');
    const achieved = sent / STEP_SECONDS;
    // Missed: too slow, too many failures, or the test could not even send at the rate (every slot was waiting).
    const meets = p95 !== undefined && p95 < TARGET_P95_MS && errorPct < TARGET_ERROR_PCT && dropped <= rate * STEP_SECONDS * 0.05;
    if (!meets && saturation === null) saturation = rate;
    if (meets && saturation === null) lastGood = rate;
    rows.push(`| ${rate} | ${achieved.toFixed(1)} | ${ms(value(d, 'med'))} | ${ms(p95)} | ${ms(value(d, 'p(99)'))} | ${ms(value(d, 'max'))} | ${errorPct.toFixed(1)}% | ${blockedPct.toFixed(1)}% | ${dropped} | ${meets ? 'yes' : '**no**'} |`);
  }
  const verdict = saturation === null
    ? `Every step met the target. **No saturation point was reached: raise RATES and run again.**`
    : lastGood === null
      ? `**No step met the target**, not even ${RATES[0]} request(s) a second: check the service is awake and healthy, then run again.`
      : `**Saturation point: ${saturation} requests a second.** The service meets the target up to ${lastGood} requests a second and stops meeting it at ${saturation}.`;
  const later = saturation === null ? [] : RATES.filter((r) => r > saturation && rows[RATES.indexOf(r)].endsWith('| yes |'));
  const noisy = later.length
    ? `\n\nA faster step (${later.join(', ')} req/s) met the target again, so the miss may be noise: run again before quoting it.`
    : '';

  const report = [
    `## Load test, ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC`,
    '',
    `Service: ${BASE}. Target: p95 under ${TARGET_P95_MS} ms and fewer than ${TARGET_ERROR_PCT}% failed requests. ${STEP_SECONDS} s per step after a ${WARM_UP_SECONDS} s warm-up.`,
    '',
    verdict + noisy,
    '',
    '| Asked (req/s) | Sent (req/s) | p50 ms | p95 ms | p99 ms | max ms | Failed | Blocked by host | Not sent | Meets target |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ...rows,
    '',
    '"Not sent": requests the test could not start on time because every virtual user was still waiting for an answer.',
    '',
  ].join('\n');
  return {
    stdout: report,
    'loadtest-report.md': report,
    'loadtest-summary.json': JSON.stringify(data, null, 2),
  };
}
