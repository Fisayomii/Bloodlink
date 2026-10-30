// Load test: response times at the 50th, 95th and 99th percentiles, step by step, and the step where the service stops
// meeting its target (docs/performance.md).
//
//   k6 run -e API_URL=https://bloodlink-o2ie.onrender.com loadtest/bloodlink.js
//
// Normally run from GitHub Actions (workflow "load-test", started by hand), so every run measures from the same place.
// Options, all optional: RATES (requests a second for each step, default 1,2,3,4,6,8,10,13,16,20), STEP_SECONDS (45),
// TARGET_P95_MS (500, for screens), TARGET_SIGN_IN_P95_MS (1000), TARGET_ERROR_PCT (1).
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
const TARGET_SIGN_IN_P95_MS = Number(__ENV.TARGET_SIGN_IN_P95_MS || 1000);
const TARGET_ERROR_PCT = Number(__ENV.TARGET_ERROR_PCT || 1);
// An answer slower than this counts as failed: nobody waits that long for a screen.
const REQUEST_TIMEOUT = '10s';
const RUN = __ENV.RUN_ID || `${Date.now()}`;
const PASSWORD = 'Demo-Password-1';

if (!BASE) throw new Error('Set API_URL, for example -e API_URL=https://bloodlink-o2ie.onrender.com');

// A request the service answered with something other than what the journey expects (any status but the expected
// one, or no answer within the timeout).
const failed = new Rate('failed');
// A 403 or 429 that did not come from the service (no JSON body in the contract's Error shape): the host's firewall.
const blockedByHost = new Rate('blocked_by_host');

const GROUPS = ['A', 'B', 'AB', 'O'];
const RHESUS = ['POSITIVE', 'NEGATIVE'];
const COMPONENTS = ['WHOLE_BLOOD', 'RED_CELLS', 'PLATELETS', 'PLASMA', 'CRYOPRECIPITATE'];
const pick = (list) => list[Math.floor(Math.random() * list.length)];

// The mix, by share of requests: what hospitals, banks and donors do most. "screen" requests are held to
// TARGET_P95_MS. Sign-in has its own target: its password check (bcrypt) is slow on purpose, so that guessing
// passwords is expensive, and a person signs in once per eight-hour session.
const MIX = [
  { share: 30, name: 'availability search', kind: 'screen', as: 'hospital', expected: 200,
    path: () => `/availability?bloodGroup=${pick(GROUPS)}&rhesus=${pick(RHESUS)}&component=${pick(COMPONENTS)}` },
  { share: 20, name: "bank's requests", kind: 'screen', as: 'bank', expected: 200, path: () => '/requests?size=20' },
  { share: 15, name: 'dashboard', kind: 'screen', as: 'bank', expected: 200, path: () => '/dashboard' },
  { share: 15, name: 'donation slots', kind: 'screen', as: 'donor', expected: 200, path: () => '/donation-slots' },
  { share: 10, name: "bank's units", kind: 'screen', as: 'bank', expected: 200, path: () => '/units?size=20' },
  { share: 5, name: "donor's bookings", kind: 'screen', as: 'donor', expected: 200, path: () => '/bookings' },
  { share: 5, name: 'sign-in', kind: 'sign-in', as: null, expected: 201, path: () => '/sessions' },
];
const TOTAL_SHARE = MIX.reduce((sum, m) => sum + m.share, 0);

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

// A threshold makes k6 keep that slice of the figures separately. The limits on screens, sign-in and failures are the
// target; they do not stop the test (the point is to go past the target). The rest only keep figures.
function thresholds() {
  const t = {};
  for (const s of RATES.map(stepName)) {
    t[`http_req_duration{scenario:${s}}`] = ['max>=0'];
    t[`http_req_duration{scenario:${s},kind:screen}`] = [`p(95)<${TARGET_P95_MS}`];
    t[`http_req_duration{scenario:${s},kind:sign-in}`] = [`p(95)<${TARGET_SIGN_IN_P95_MS}`];
    t[`failed{scenario:${s}}`] = [`rate<${TARGET_ERROR_PCT / 100}`];
    t[`blocked_by_host{scenario:${s}}`] = ['rate<1'];
    t[`iterations{scenario:${s}}`] = ['count>=0'];
    t[`dropped_iterations{scenario:${s}}`] = ['count>=0'];
    for (const m of MIX) t[`http_req_duration{scenario:${s},name:${m.name}}`] = ['max>=0'];
  }
  return t;
}

export const options = {
  scenarios: steps(),
  thresholds: thresholds(),
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max', 'count'],
  userAgent: 'bloodlink-load-test',
  setupTimeout: '240s',
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

const median = (list) => {
  const sorted = [...list].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};

export function setup() {
  // The free host sleeps after 15 idle minutes; wake it before anything is timed.
  for (let i = 0; i < 18; i++) {
    const r = http.get(`${BASE}/actuator/health`, { timeout: '60s', headers: headers(null, 'wake') });
    if (r.status === 200) break;
  }
  // The floor: the health check does no database work, so its time is mostly the network trip from the test machine
  // to the host, plus the host's own proxy. Every request in the results includes it.
  const floor = [];
  for (let i = 0; i < 20; i++) {
    floor.push(http.get(`${BASE}/actuator/health`, { headers: headers(null, `floor-${i}`) }).timings.duration);
  }
  const bank = signIn('staff@lagos-central.bank.demo');
  return {
    bank: bank.accessToken,
    hospital: signIn('staff@yaba.hospital.demo').accessToken,
    donor: signIn('donor@bloodlink.demo').accessToken,
    floorMs: median(floor),
  };
}

let counter = 0;

export default function (tokens) {
  let roll = Math.random() * TOTAL_SHARE;
  let m = MIX[MIX.length - 1];
  for (const candidate of MIX) {
    if ((roll -= candidate.share) < 0) { m = candidate; break; }
  }
  const n = `${__VU}-${++counter}`;
  const params = { headers: headers(m.as ? tokens[m.as] : null, n), tags: { name: m.name, kind: m.kind },
    timeout: REQUEST_TIMEOUT };
  const r = m.kind === 'sign-in'
    ? http.post(`${BASE}${m.path()}`, JSON.stringify({
      email: pick(['staff@lekki.bank.demo', 'staff@vi.hospital.demo', 'donor2@bloodlink.demo']), password: PASSWORD }), params)
    : http.get(`${BASE}${m.path()}`, params);

  const ok = r.status === m.expected;
  failed.add(!ok);
  blockedByHost.add((r.status === 403 || r.status === 429) && !(r.body || '').includes('"requestId"'));
  check(r, { [`${m.name}: ${m.expected}`]: () => ok });
}

// The report: one row per step, the first step that misses the target, and each request's p95 per step.
export function handleSummary(data) {
  const metrics = data.metrics;
  // A slice with no requests in it has no figures: shown as a dash, not as 0 ms.
  const value = (key, stat) => {
    const v = metrics[key] && metrics[key].values;
    if (!v || (v.count === 0 && stat !== 'count')) return undefined;
    return v[stat];
  };
  const ms = (v) => (v === undefined ? '—' : `${Math.round(v)}`);
  const rows = [];
  const meetsByRate = {};
  for (const rate of RATES) {
    const s = stepName(rate);
    const all = `http_req_duration{scenario:${s}}`;
    const screens = `http_req_duration{scenario:${s},kind:screen}`;
    const signIns = `http_req_duration{scenario:${s},kind:sign-in}`;
    const sent = value(`iterations{scenario:${s}}`, 'count') || 0;
    const dropped = value(`dropped_iterations{scenario:${s}}`, 'count') || 0;
    const errorPct = 100 * (value(`failed{scenario:${s}}`, 'rate') || 0);
    const blockedPct = 100 * (value(`blocked_by_host{scenario:${s}}`, 'rate') || 0);
    const screenP95 = value(screens, 'p(95)');
    const signInP95 = value(signIns, 'p(95)');
    // Missed: screens or sign-in too slow, too many failures, or the test could not even send at the rate.
    const meets = screenP95 !== undefined && screenP95 < TARGET_P95_MS
      && (signInP95 === undefined || signInP95 < TARGET_SIGN_IN_P95_MS)
      && errorPct < TARGET_ERROR_PCT && dropped <= rate * STEP_SECONDS * 0.05;
    meetsByRate[rate] = meets;
    rows.push(`| ${rate} | ${(sent / STEP_SECONDS).toFixed(1)} | ${ms(value(all, 'med'))} | ${ms(value(all, 'p(95)'))} | ${ms(value(all, 'p(99)'))} | ${ms(value(screens, 'med'))} | ${ms(screenP95)} | ${ms(value(screens, 'p(99)'))} | ${ms(signInP95)} | ${errorPct.toFixed(1)}% | ${blockedPct.toFixed(1)}% | ${dropped} | ${meets ? 'yes' : '**no**'} |`);
  }
  const saturation = RATES.find((r) => !meetsByRate[r]);
  const lastGood = RATES.filter((r) => saturation === undefined || r < saturation).pop();
  let verdict;
  if (saturation === undefined) {
    verdict = '**Every step met the target. No saturation point was reached: raise RATES and run again.**';
  } else if (lastGood === undefined) {
    verdict = `**No step met the target**, not even ${RATES[0]} request(s) a second. See the table by request for which one misses.`;
  } else {
    verdict = `**Saturation point: ${saturation} requests a second.** The service meets the target up to ${lastGood} requests a second and stops meeting it at ${saturation}.`;
    const later = RATES.filter((r) => r > saturation && meetsByRate[r]);
    if (later.length) verdict += ` A faster step (${later.join(', ')} req/s) met it again, so run again before quoting it.`;
  }

  const byRequest = MIX.map((m) => `| ${m.name} (${m.share}%) | ${RATES.map((rate) =>
    ms(value(`http_req_duration{scenario:${stepName(rate)},name:${m.name}}`, 'p(95)'))).join(' | ')} |`);

  const floor = data.setup_data && data.setup_data.floorMs;
  const report = [
    `## Load test, ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC`,
    '',
    `Service: ${BASE}. Target: screens p95 under ${TARGET_P95_MS} ms, sign-in p95 under ${TARGET_SIGN_IN_P95_MS} ms, fewer than ${TARGET_ERROR_PCT}% failed (no answer in ${REQUEST_TIMEOUT} counts as failed). ${STEP_SECONDS} s per step after a ${WARM_UP_SECONDS} s warm-up.`,
    '',
    `Network floor: the health check, which touches no database, takes **${ms(floor)} ms** (median of 20) from the test machine. Every figure below includes it.`,
    '',
    verdict,
    '',
    '| Asked (req/s) | Sent (req/s) | All p50 | All p95 | All p99 | Screens p50 | Screens p95 | Screens p99 | Sign-in p95 | Failed | Blocked by host | Not sent | Meets target |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ...rows,
    '',
    'Times in milliseconds. "Not sent": requests the test could not start on time because every virtual user was still waiting for an answer.',
    '',
    '### p95 by request (ms), per step',
    '',
    `| Request (share) | ${RATES.map((r) => `${r}/s`).join(' | ')} |`,
    `| --- | ${RATES.map(() => '---').join(' | ')} |`,
    ...byRequest,
    '',
  ].join('\n');
  return {
    stdout: report,
    'loadtest-report.md': report,
    'loadtest-summary.json': JSON.stringify(data, null, 2),
  };
}
