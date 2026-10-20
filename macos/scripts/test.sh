#!/bin/sh
# Runs the HandspanCore tests. With only the Command Line Tools installed, an
# incremental test build sometimes loses the Swift Testing macro plugin
# ("plugin for module 'TestingMacros' not found"); a second build succeeds.
cd "$(dirname "$0")/.." || exit 1
log=$(mktemp)
swift test "$@" >"$log" 2>&1
status=$?
if [ $status -ne 0 ] && grep -q "plugin for module 'TestingMacros' not found" "$log"; then
  swift test "$@" >"$log" 2>&1
  status=$?
fi
grep -vE "search path .* not found" "$log"
rm -f "$log"
exit $status
