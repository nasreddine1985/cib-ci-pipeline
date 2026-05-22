#!/usr/bin/env bash
# Start / stop the local CIB infrastructure (Bitbucket + Jenkins).
# Usage:
#   ./test/start-local-infra.sh          # start
#   ./test/start-local-infra.sh stop     # stop (keeps volumes)
#   ./test/start-local-infra.sh destroy  # stop + remove all volumes

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
    echo "Services starting..."
    echo "  Bitbucket : http://localhost:7990"
    echo "  Jenkins   : http://localhost:8080"
    echo ""
    echo "Bitbucket takes ~2 min on first boot. Get the Jenkins unlock password with:"
    echo "  docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword"
    ;;
esac
