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

# Time budget per account, in seconds. Fuzzing keeps generating requests until it is spent, and a request that gets
# no answer is reported as a failure instead of hanging the pipeline.
MAX_TIME="${CONTRACT_TEST_MAX_TIME:-120}"

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
      --max-time "${MAX_TIME}" \
      --phases examples,coverage,fuzzing \
      --workers 2 \
      --request-timeout 20 \
      --seed 26102026 \
      --no-color \
      --report junit --report-junit-path "${report}"; then
    status=1
  fi
  echo "::endgroup::"
done
exit "${status}"
