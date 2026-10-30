#!/usr/bin/env bash
# Contract tests: does the running service still answer exactly as contract/openapi.yaml says?
#
#   scripts/contract-test.sh <service address> <examples per operation> <account> [<account> ...]
#
# Schemathesis generates requests from the contract (valid and invalid ones) and checks every response: no server
# error, a status code the contract lists for that operation, the right content type, and a body matching the schema.
# It runs once per demo account given, so each role's answers are checked (password Demo-Password-1 for all).
# Any divergence makes this script fail, and the pipeline does not deploy.
#
# In the pipeline it runs twice: before deploying, against the new build started in CI with a throwaway database
# (every role, more examples), and after deploying, against the live service (administrator only, so it creates no data).
set -euo pipefail

BASE_URL="${1:?service address}"
EXAMPLES="${2:?examples per operation}"
shift 2
ACCOUNTS=("$@")

# Paths in the contract that the service does not implement yet. They answer 404, which the contract does not list for
# them, so they are left out until built. Remove each one in the change that builds it (decisions file, 2026-10-27).
NOT_YET_BUILT='^/dashboard'

# The same requests every time, for the same commit: a fixed number per operation (no time budget), a fixed seed and
# one worker, so the order never changes. A pull request that passes therefore passes again on main. With a time budget
# and two workers, a faster or slower runner sent different requests, and main found a failure its pull request had
# not (30 October). A request that gets no answer still fails after 20 seconds, and the job has a hard time limit.
# To explore new inputs on purpose, change SEED in its own pull request.
SEED="${CONTRACT_TEST_SEED:-26102026}"

# Which Schemathesis phases run. All three against the build in CI. Against the live service the pipeline sets
# CONTRACT_TEST_PHASES=examples,coverage: random fuzzing sends attack-like headers (for example "__proto__"), and
# Render's own firewall blocks those with an HTML 403 before they reach the service (found 30 October).
PHASES="${CONTRACT_TEST_PHASES:-examples,coverage,fuzzing}"

CHECKS=not_a_server_error,status_code_conformance,content_type_conformance,response_schema_conformance

status=0
for account in "${ACCOUNTS[@]}"; do
  echo "::group::Contract tests as ${account}"
  token=$(curl -fsS -X POST "${BASE_URL}/sessions" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${account}\",\"password\":\"Demo-Password-1\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
  report="contract-test-report/${account%%@*}.xml"
  mkdir -p contract-test-report
  if ! st run contract/openapi.yaml \
      --url "${BASE_URL}" \
      --header "Authorization: Bearer ${token}" \
      --checks "${CHECKS}" \
      --exclude-path-regex "${NOT_YET_BUILT}" \
      --max-examples "${EXAMPLES}" \
      --phases "${PHASES}" \
      --workers 1 \
      --request-timeout 20 \
      --seed "${SEED}" \
      --no-color \
      --report junit --report-junit-path "${report}"; then
    status=1
  fi
  echo "::endgroup::"
done
exit "${status}"
