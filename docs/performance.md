# Performance: target, load test and saturation point

Brief: "A load test reporting response times at the 50th, 95th and 99th percentiles and identifying the point where the
service stops meeting the target you set." Also "Performance target published and measured" and "Performance measured
on the deployed address".

## The target

**95% of requests answered in under 500 ms, and fewer than 1% failing, for the mix below, on the deployed service.**

Why these numbers: every request in the mix is a screen a hospital or bank worker is waiting on, often during an
emergency. Half a second still feels immediate. The 95th percentile is used rather than the average, because the
average hides the slow answers that people remember. Set on 30 Oct, before the first run.

**Not covered by the target:** the first request after the free service has slept (15 idle minutes). It waits for the
service to start, measured at 28 s, and is a declared limitation (README). Each run wakes the service first, so it is
not counted.

## What the test does

`loadtest/bloodlink.js` ([k6](https://k6.io), open source), run from GitHub Actions (`load-test` workflow, started by
hand) against `https://bloodlink-o2ie.onrender.com`, so every run measures from the same place:

1. Wakes the service and signs in a bank, a hospital and a donor (not timed).
2. 30 s warm-up at 1 request a second (not counted).
3. Steps of 45 s at 1, 2, 3, 4, 6, 8, 10, 13, 16 and 20 requests a second. Requests are sent at that rate whatever the
   service does (an "open" model), as real users do. A slow service makes requests queue up, not slow the test down.
4. For each step: p50, p95, p99 and maximum time, failed requests, requests blocked by the host's firewall, and requests
   the test could not even start on time. The **saturation point** is the first step that misses the target.

| Share | Request | As |
| --- | --- | --- |
| 30% | `GET /availability` with a random group, rhesus and component | Hospital staff |
| 20% | `GET /requests?size=20` (the bank's request list) | Bank staff |
| 15% | `GET /dashboard` | Bank staff |
| 15% | `GET /donation-slots` (next 14 days) | Donor |
| 10% | `GET /units?size=20` | Bank staff |
| 5% | `GET /bookings` | Donor |
| 5% | `POST /sessions` (sign-in, password check with bcrypt) | Several accounts |

**Reads and sign-ins only.** Writing at load would fill the live demonstration data with thousands of requests,
holds and bookings. The writes' correctness under concurrent use is proved separately (`ConcurrentApprovalIT`,
`ConcurrentBookingIT`, and the rule check on the deployed service).

Every request carries `X-Request-Id: load-<run>-<n>`, so the service's logs show which lines came from a test run.

### Run it

- **From GitHub:** Actions > load-test > Run workflow. About nine minutes. The table appears on the run's summary page,
  and as the `load-test-report` artifact (with k6's full JSON).
- **From a laptop:** install k6, then `k6 run -e API_URL=https://bloodlink-o2ie.onrender.com loadtest/bloodlink.js`.
  Options: `RATES`, `STEP_SECONDS`, `TARGET_P95_MS`, `TARGET_ERROR_PCT`.

## What it runs on

Render's free instance: 0.1 CPU and 512 MB of memory ([Render instance types](https://render.com/docs/compute-plans),
checked 30 Oct), Frankfurt. Neon's free database, also Frankfurt. The test runs from GitHub's runners, so each result
also includes the network trip from there.

## Results

To be filled in from the first run (31 Oct).

| Asked (req/s) | Sent (req/s) | p50 ms | p95 ms | p99 ms | max ms | Failed | Blocked by host | Not sent | Meets target |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

**Saturation point:** —

**What limits it:** —

**What would move it:** —
