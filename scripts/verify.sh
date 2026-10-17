#!/bin/sh
set -eu
repository=${1:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
run_id=${2:-verify-$(date +%Y%m%d%H%M%S)-$$}
case "$run_id" in
  verify-?*) ;;
  *) echo 'Invalid verification run ID.' >&2; exit 2 ;;
esac
case "$run_id" in
  *[!a-zA-Z0-9-]*) echo 'Invalid verification run ID.' >&2; exit 2 ;;
esac
cd -- "$repository"
if [ -e "target/harness/$run_id" ]; then
  echo 'Verification output must be fresh.' >&2
  exit 2
fi
exec sh ./mvnw -B -ntp "-Dgateway.build-directory=target/harness/$run_id" verify
