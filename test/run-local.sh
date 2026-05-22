#!/usr/bin/env bash
# Run the CIB pipeline locally using Jenkinsfile Runner.
# Usage:
#   ./test/run-local.sh               # auto-detect (acts like a PR since no real branch context)
#   ./test/run-local.sh dev           # simulate a push to dev
#   ./test/run-local.sh labels/sec    # simulate a push to labels/sec branch
#   ./test/run-local.sh CONFIGURE_DEV # run the CONFIGURE_DEV manual flow
#   ./test/run-local.sh RELEASE       # run the RELEASE manual flow

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIPELINE_TYPE="AUTO"
BRANCH_NAME="dev"
CHANGE_ID=""

case "${1:-}" in
  CONFIGURE_DEV|RELEASE)
    PIPELINE_TYPE="$1"
    ;;
  "")
    # default: PR simulation
    CHANGE_ID="42"
    BRANCH_NAME="feature/test"
    ;;
  *)
    BRANCH_NAME="$1"
    ;;
esac

echo "=== CIB Pipeline Local Test ==="
echo "  Branch       : ${BRANCH_NAME}"
echo "  PIPELINE_TYPE: ${PIPELINE_TYPE}"
echo "  IS_PR        : ${CHANGE_ID:+true}${CHANGE_ID:-false}"
echo ""

docker run --rm \
  --name cib-ci-pipeline-test \
  -v "${REPO_ROOT}:/workspace" \
  -e BRANCH_NAME="${BRANCH_NAME}" \
  -e CHANGE_ID="${CHANGE_ID}" \
  -e BUILD_NUMBER="1" \
  -e JOB_BASE_NAME="cib-local-test" \
  -e JOB_NAME="cib-local-test" \
  jenkins/jenkinsfile-runner:latest \
  --no-sandbox \
  --file /workspace/test/Jenkinsfile.local \
  --shared-library "name=cib-ci-pipeline,version=local,path=/workspace"
