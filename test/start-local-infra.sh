#!/usr/bin/env bash
# Start / stop the local Jenkins instance.
# Usage:
#   ./test/start-local-infra.sh          # start
#   ./test/start-local-infra.sh stop     # stop (keeps volume)
#   ./test/start-local-infra.sh destroy  # stop + remove volume

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

case "${1:-}" in
  stop)
    docker compose -f "${SCRIPT_DIR}/docker-compose.yml" stop
    ;;
  destroy)
    docker compose -f "${SCRIPT_DIR}/docker-compose.yml" down -v
    ;;
  *)
    docker compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d
    echo ""
    echo "Jenkins starting at http://localhost:8090"
    echo ""
    echo "Get the unlock password with:"
    echo "  docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword"
    ;;
esac
