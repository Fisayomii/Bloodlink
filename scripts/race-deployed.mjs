// The key rule, checked against the DEPLOYED service: three hospitals ask for the last unit of a group, the bank's three
// approvals are sent at the same moment, and exactly one may succeed.
//
//   node scripts/race-deployed.mjs https://bloodlink-o2ie.onrender.com
//
// Needs Node 22 (built-in fetch) and the demo accounts. Each run adds one unit at Lekki (reference RACE-<time>) and three
// requests, then declines the two that lost, so it leaves a visible, labelled trace in the history. It never touches
// the Lagos Central O-negative unit used in the live demo. Exit code 0 = rule held, 1 = rule broken
// or the run could not be set up. ConcurrentApprovalIT proves the same rule in CI and is the test shown failing without
// the protection (docs/evidence.md).

const base = (process.argv[2] || process.env.API_URL || '').replace(/\/+$/, '');
if (!base) {
  console.error('Usage: node scripts/race-deployed.mjs <service address>');
  process.exit(1);
}
const PASSWORD = 'Demo-Password-1';
const BANK = 'staff@lekki.bank.demo';
const HOSPITALS = ['staff@yaba.hospital.demo', 'staff@ikorodu.hospital.demo', 'staff@vi.hospital.demo'];
const GROUPS = ['A', 'B', 'AB', 'O'];
const RHESUS = ['POSITIVE', 'NEGATIVE'];
const COMPONENTS = ['WHOLE_BLOOD', 'RED_CELLS', 'PLATELETS', 'PLASMA', 'CRYOPRECIPITATE'];
const runId = `race-${Date.now()}`;

async function call(method, path, { token, body, headers = {} } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: {
      'X-Request-Id': `${runId}-${Math.random().toString(36).slice(2, 8)}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

async function signIn(email) {
  const r = await call('POST', '/sessions', { body: { email, password: PASSWORD } });
  if (r.status !== 201) throw new Error(`Sign-in failed for ${email}: ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}

function isoDate(daysFromNow) {
  return new Date(Date.now() + daysFromNow * 86_400_000).toISOString().slice(0, 10);
}

async function main() {
  console.log(`Run ${runId} against ${base}`);
  // Wake the service: the free host sleeps.
  for (let i = 0; i < 12; i++) {
    try {
      if ((await call('GET', '/actuator/health')).status === 200) break;
    } catch { /* still waking */ }
    await new Promise((resolve) => setTimeout(resolve, 10_000));
  }

  const bank = await signIn(BANK);
  const bankId = bank.user.organisation.id;
  const hospitals = await Promise.all(HOSPITALS.map(signIn));

  // A group and component this bank has none of, so the unit added below is the last (only) one.
  let combo = null;
  for (const bloodGroup of GROUPS) {
    for (const rhesus of RHESUS) {
      for (const component of COMPONENTS) {
        if (combo) break;
        const q = `/availability?bloodGroup=${bloodGroup}&rhesus=${rhesus}&component=${component}&bankId=${bankId}`;
        const r = await call('GET', q, { token: hospitals[0].accessToken });
        if (r.status === 200 && r.json.items.length === 0) combo = { bloodGroup, rhesus, component };
      }
    }
  }
  if (!combo) throw new Error('Every combination already has stock at Lekki; nothing to race for.');
  console.log(`Racing for ${combo.bloodGroup} ${combo.rhesus} ${combo.component} at Lekki`);

  const unit = await call('POST', '/units', {
    token: bank.accessToken,
    body: { reference: `RACE-${Date.now()}`, ...combo, collectionDate: isoDate(0), expiryDate: isoDate(30) },
  });
  if (unit.status !== 201) throw new Error(`Could not record the unit: ${unit.status} ${JSON.stringify(unit.json)}`);

  const requestIds = [];
  for (const hospital of hospitals) {
    const r = await call('POST', '/requests', {
      token: hospital.accessToken,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { bankId, ...combo, quantity: 1, urgency: 'EMERGENCY', neededBy: new Date(Date.now() + 4 * 3_600_000).toISOString() },
    });
    if (r.status !== 201) throw new Error(`Request failed: ${r.status} ${JSON.stringify(r.json)}`);
    requestIds.push(r.json.id);
  }

  // The race: all three approvals leave together.
  const results = await Promise.all(
    requestIds.map((id) => call('POST', `/requests/${id}/approval`, { token: bank.accessToken })));
  const statuses = results.map((r) => r.status).sort();
  console.log('Approval responses:', statuses.join(', '));
  results.filter((r) => r.status === 409).forEach((r) => console.log('  refused:', r.json.code, '-', r.json.message));

  const states = [];
  for (const id of requestIds) states.push((await call('GET', `/requests/${id}`, { token: bank.accessToken })).json.state);
  console.log('Request states:', states.sort().join(', '));

  const held = statuses.join(',') === '201,409,409'
    && states.join(',') === 'APPROVED,PENDING,PENDING'
    && results.filter((r) => r.status === 409).every((r) => r.json.code === 'UNITS_NO_LONGER_AVAILABLE');
  console.log(held ? 'PASS: exactly one reservation for the last unit.' : 'FAIL: the rule did not hold.');

  // Tidy up so the demo hospitals' pending lists stay readable: decline the requests that lost.
  for (const id of requestIds) {
    await call('POST', `/requests/${id}/declination`, {
      token: bank.accessToken,
      body: { reason: `Automated rule check ${runId}: unit already reserved by another request` },
    });
  }
  process.exit(held ? 0 : 1);
}

main().catch((error) => {
  console.error('Could not run:', error.message);
  process.exit(1);
});
