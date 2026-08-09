#!/usr/bin/env bash

set -euo pipefail

: "${SLS_FIXTURE_SERVER_ROOT:?Set SLS_FIXTURE_SERVER_ROOT to the read-only fixture server root}"
: "${SLS_REPOSITORY_ROOT:=/repo}"

rc1_jar="${SLS_REPOSITORY_ROOT}/.local-fixtures/rc2-upgrade/sls-lite-0.1.0-rc.1.jar"
current_jar="${SLS_REPOSITORY_ROOT}/target/sls-lite-0.1.0-rc.1.jar"
data=plugins/sls-lite

for required in "${SLS_FIXTURE_SERVER_ROOT}/velocity.jar" \
  "${SLS_FIXTURE_SERVER_ROOT}/velocity.toml" \
  "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/software-profiles/paper-auto.yml" \
  "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/software/paper-auto/1.18.2" \
  "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/runtimes/java-17" \
  "${rc1_jar}" "${current_jar}"; do
  test -e "${required}"
done

cp "${SLS_FIXTURE_SERVER_ROOT}/velocity.jar" velocity.jar
cp "${SLS_FIXTURE_SERVER_ROOT}/velocity.toml" velocity.toml
mkdir -p plugins "${data}/software-profiles" "${data}/software/paper-auto" \
  "${data}/runtimes" "${data}/blueprints/upgrade"
cp "${rc1_jar}" plugins/sls-lite.jar
cp "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/software-profiles/paper-auto.yml" \
  "${data}/software-profiles/"
cp -a "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/software/paper-auto/1.18.2" \
  "${data}/software/paper-auto/"
cp -a "${SLS_FIXTURE_SERVER_ROOT}/plugins/sls-lite/runtimes/java-17" "${data}/runtimes/"
cp "${SLS_REPOSITORY_ROOT}/scripts/fixtures/rc1-upgrade-running.yml" \
  "${data}/blueprints/upgrade/"

wait_for_log() {
  local pattern="$1"
  local log="$2"
  local attempts="${3:-120}"
  for ((attempt = 0; attempt < attempts; attempt++)); do
    if grep -Eq "${pattern}" "${log}"; then
      return 0
    fi
    sleep 1
  done
  tail -n 120 "${log}"
  return 1
}

start_proxy() {
  local log="$1"
  local fifo="$2"
  rm -f "${fifo}"
  mkfifo "${fifo}"
  exec 3<>"${fifo}"
  java -Xms128M -Xmx256M -jar velocity.jar <"${fifo}" >"${log}" 2>&1 &
  proxy_pid=$!
  wait_for_log 'SLS-LITE initialized' "${log}" 30
}

stop_proxy() {
  local log="$1"
  printf 'shutdown\n' >&3
  for ((attempt = 0; attempt < 90; attempt++)); do
    if ! kill -0 "${proxy_pid}" 2>/dev/null; then
      wait "${proxy_pid}"
      exec 3>&-
      return 0
    fi
    sleep 1
  done
  tail -n 120 "${log}"
  return 1
}

start_proxy /tmp/sls-rc1-running.log /tmp/sls-rc1-input
printf 'sls start rc1-upgrade-running\n' >&3
wait_for_log 'Instance ready.*rc1-upgrade-running\.' /tmp/sls-rc1-running.log 120
instance_id="$(grep -Eo 'rc1-upgrade-running\.[a-z0-9]+' /tmp/sls-rc1-running.log | tail -n 1)"
test -n "${instance_id}"
metadata="${data}/instances/${instance_id}/.sls-lite-instance.properties"
grep -q '^state=READY$' "${metadata}"
grep -q '^process_id=' "${metadata}"

# Simulate the upgrade after an unclean proxy exit. The managed Paper child is deliberately left
# alive so the newer build must verify and terminate the recorded process during reconciliation.
kill -KILL "${proxy_pid}"
set +e
wait "${proxy_pid}"
set -e
exec 3>&-
grep -q '^state=READY$' "${metadata}"
recorded_child="$(sed -n 's/^process_id=//p' "${metadata}")"
kill -0 "${recorded_child}"

# Introduce the RC.2-only mapping after the RC.1 instance exists. Restart must reject structural
# drift; explicit reset is the operator-authorized assembly boundary that imports the new file.
mkdir -p "${data}/volumes/whitelists/rc2-instance-upgrade"
cp "${SLS_REPOSITORY_ROOT}/scripts/fixtures/rc2-upgrade-whitelist.json" \
  "${data}/volumes/whitelists/rc2-instance-upgrade/whitelist.json"
cp "${SLS_REPOSITORY_ROOT}/scripts/fixtures/rc2-upgrade-running-persistent.yml" \
  "${data}/blueprints/upgrade/rc1-upgrade-running.yml"

cp "${current_jar}" plugins/sls-lite.jar
start_proxy /tmp/sls-rc2-reconcile.log /tmp/sls-rc2-input
wait_for_log 'Instance reconciliation.*1 persistent preserved.*0 failure\(s\)' \
  /tmp/sls-rc2-reconcile.log 30
grep -q '^state=STOPPED$' "${metadata}"
! grep -q '^process_id=' "${metadata}"
if kill -0 "${recorded_child}" 2>/dev/null; then
  echo "Recorded RC.1 child remained alive after reconciliation: ${recorded_child}" >&2
  exit 1
fi

printf 'sls restart %s\n' "${instance_id}" >&3
wait_for_log "${instance_id} was created from a different software, configuration, or volume definition" \
  /tmp/sls-rc2-reconcile.log 30
printf 'sls reset %s\n' "${instance_id}" >&3
wait_for_log "Instance ready.*${instance_id}" /tmp/sls-rc2-reconcile.log 120
test -f "${data}/instances/${instance_id}/.sls-lite-persistent-files.properties"
cmp -s "${data}/volumes/whitelists/rc2-instance-upgrade/whitelist.json" \
  "${data}/instances/${instance_id}/whitelist.json"
printf 'sls stop %s\n' "${instance_id}" >&3
wait_for_log "Instance process exited: ${instance_id}" /tmp/sls-rc2-reconcile.log 90
stop_proxy /tmp/sls-rc2-reconcile.log

test -d "${data}/instances/${instance_id}"
grep -q '^state=STOPPED$' "${metadata}"
! grep -q '^process_id=' "${metadata}"
echo "SLS_INSTANCE_UPGRADE_ID=${instance_id}"
grep -E "Stopped orphaned|Instance reconciliation|Instance ready.*${instance_id}|Instance process exited: ${instance_id}" \
  /tmp/sls-rc2-reconcile.log | tail -n 20
