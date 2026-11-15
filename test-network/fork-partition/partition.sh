#!/usr/bin/env bash
# Toggle the Teku fork partition test mode in a kurtosis enclave, or show per-node heads.
#
#   partition.sh <enclave> on|off|status [service-filter...]
#
# Filters are substrings of the CL service names (e.g. cl-1 cl-2). Without filters every
# Teku beacon node in the enclave is targeted.
set -euo pipefail

CONTROL_FILE="${TEKU_FORK_PARTITION_FILE:-/tmp/teku-fork-partition}"

usage() {
  echo "usage: $0 <enclave> on|off|status [service-filter...]" >&2
  exit 1
}

[[ $# -ge 2 ]] || usage
ENCLAVE="$1"
ACTION="$2"
shift 2

services() {
  local all
  all=$(kurtosis enclave inspect "$ENCLAVE" 2>/dev/null | grep -oE 'cl-[0-9]+-teku-[a-z0-9-]+' | sort -u)
  if [[ $# -eq 0 ]]; then
    echo "$all"
    return
  fi
  local svc filter
  for svc in $all; do
    for filter in "$@"; do
      if [[ "$svc" == *"$filter"* ]]; then
        echo "$svc"
        break
      fi
    done
  done
}

SERVICES=$(services "$@")
[[ -n "$SERVICES" ]] || { echo "no matching Teku services in enclave $ENCLAVE" >&2; exit 1; }

case "$ACTION" in
  on | off)
    if [[ "$ACTION" == on ]]; then cmd="touch $CONTROL_FILE"; else cmd="rm -f $CONTROL_FILE"; fi
    for svc in $SERVICES; do
      kurtosis service exec "$ENCLAVE" "$svc" "$cmd" >/dev/null 2>&1 &
    done
    wait
    echo "partition $ACTION: $(echo $SERVICES | tr '\n' ' ')"
    ;;
  status)
    printf '%-22s %-9s %-6s %-12s %-6s %-6s %-8s\n' service partition slot head justif final syncing
    for svc in $SERVICES; do
      url=$(kurtosis port print "$ENCLAVE" "$svc" http 2>/dev/null)
      header=$(curl -s "$url/eth/v1/beacon/headers/head")
      fin=$(curl -s "$url/eth/v1/beacon/states/head/finality_checkpoints")
      sync=$(curl -s "$url/eth/v1/node/syncing")
      if kurtosis service exec "$ENCLAVE" "$svc" "test -e $CONTROL_FILE" >/dev/null 2>&1; then
        mode=on
      else
        mode=off
      fi
      printf '%-22s %-9s %-6s %-12s %-6s %-6s %-8s\n' "$svc" "$mode" \
        "$(jq -r '.data.header.message.slot' <<<"$header")" \
        "$(jq -r '.data.root[0:12]' <<<"$header")" \
        "$(jq -r '.data.current_justified.epoch' <<<"$fin")" \
        "$(jq -r '.data.finalized.epoch' <<<"$fin")" \
        "$(jq -r '.data.is_syncing' <<<"$sync")"
    done
    ;;
  *)
    usage
    ;;
esac
