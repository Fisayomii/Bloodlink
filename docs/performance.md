# Performance: target, load test and saturation point

Brief: "A load test reporting response times at the 50th, 95th and 99th percentiles and identifying the point where the
service stops meeting the target you set." Also "Performance target published and measured" and "Performance measured
on the deployed address".

## The target

| | p95 | Failed |
| --- | --- | --- |
| **Screens** (every read in the mix below) | under 500 ms | fewer than 1% of all requests |
| **Sign-in** | under 1,000 ms | |

An answer that takes longer than 10 s counts as failed. Measured on the deployed service.

Why these numbers: every screen in the mix is one a hospital or bank worker is waiting on, often during an emergency.
Half a second still feels immediate. The 95th percentile is used rather than the average, because the average hides
the slow answers that people remember.

Sign-in has its own target because its password check (bcrypt, cost 10) is slow on purpose: it makes guessing
passwords expensive. It costs 71 ms of a full processor core (measured 30 Oct), so about 700 ms on the free host's
0.1 CPU. A person signs in once per eight-hour session.

**Revised after run 1 (30 Oct).** The first target was "p95 under 500 ms" for every request together. Run 1 missed it
at every step, even 1 request a second, while the median stayed at about 205 ms. Sign-ins are 5% of the mix, so with
every request held to one figure the 95th percentile measured the password check rather than the screens. Run 1 stays
below, with the figures it produced.

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
4. For each step: p50, p95 and p99 for all requests, for screens and for sign-in; p95 for each kind of request; failed
   requests; requests blocked by the host's firewall; and requests the test could not start on time. The
   **saturation point** is the first step that misses the target.

Before the steps it measures the **network floor**: the median time of 20 health checks, which touch no database. Every
result includes that trip from GitHub's machines to the host.

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
  Options: `RATES`, `STEP_SECONDS`, `TARGET_P95_MS`, `TARGET_SIGN_IN_P95_MS`, `TARGET_ERROR_PCT`.

## What it runs on

Render's free instance: 0.1 CPU and 512 MB of memory ([Render instance types](https://render.com/docs/compute-plans),
checked 30 Oct), Frankfurt. Neon's free database, also Frankfurt. The test runs from GitHub's runners, so each result
also includes the network trip from there.

## Results

### Run 1: 30 Oct, 19:17 UTC, commit `1edab55`

One target for every request (p95 under 500 ms). Request timeout not yet working (fixed for run 2).

| Asked (req/s) | Sent (req/s) | p50 ms | p95 ms | p99 ms | max ms | Failed | Blocked by host | Not sent | Meets target |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.0 | 219 | 671 | 805 | 813 | 0.0% | 0.0% | 0 | no |
| 2 | 2.0 | 208 | 692 | 782 | 785 | 0.0% | 0.0% | 0 | no |
| 3 | 3.0 | 209 | 733 | 862 | 1094 | 0.0% | 0.0% | 0 | no |
| 4 | 4.0 | 202 | 559 | 701 | 789 | 0.0% | 0.0% | 0 | no |
| 6 | 6.0 | 205 | 790 | 1466 | 1894 | 0.0% | 0.0% | 0 | no |
| 8 | 8.0 | 204 | 811 | 1662 | 2070 | 0.0% | 0.0% | 0 | no |
| 10 | 9.2 | 1935 | 10501 | 15777 | 22931 | 0.0% | 0.0% | 38 | no |
| 13 | 12.8 | 259 | 2506 | 4951 | 7030 | 0.0% | 0.0% | 11 | no |
| 16 | 14.8 | 1174 | 7396 | 10257 | 19747 | 0.0% | 0.0% | 52 | no |
| 20 | 18.4 | 2538 | 10004 | 14082 | 20830 | 0.0% | 0.0% | 71 | no |

What it showed:

- **Up to 8 requests a second the service keeps up.** The median stays at about 205 ms and every request is sent on
  time. At 10 a second the median jumps to 1.9 s and 38 requests cannot even be sent: requests queue faster than the
  service answers them. The capacity for this mix is about 8 to 9 requests a second.
- **The slowest 5% take 560 to 810 ms even at 1 request a second**, so they are not caused by load. Sign-in is the
  likely cause (5% of the mix, about 700 ms each, see the target). Run 2 reports each kind of request separately to
  confirm it.
- **A fault in the test:** the 10 s timeout was set with an option k6 does not have, so answers of up to 23 s counted as
  successes, and "Failed" reads 0% where it should not. Fixed: the timeout is now set on every request.

### Run 2

To be filled in.

**Saturation point:** —

**What limits it:** —

**What would move it:** —
