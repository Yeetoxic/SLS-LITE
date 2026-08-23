#!/usr/bin/env bash

set -euo pipefail

: "${SLS_FIXTURE_SERVER_ROOT:?Set SLS_FIXTURE_SERVER_ROOT to the read-only fixture server root}"
: "${SLS_REPOSITORY_ROOT:=/repo}"

rc1_jar="${SLS_REPOSITORY_ROOT}/.local-fixtures/rc2-upgrade/sls-lite-0.1.0-rc.1.jar"
current_jar="${SLS_REPOSITORY_ROOT}/target/sls-lite-0.1.0-rc.2.2.jar"
fixture_root="${SLS_REPOSITORY_ROOT}/scripts/fixtures"

for required in "${SLS_FIXTURE_SERVER_ROOT}/velocity.jar" \
  "${SLS_FIXTURE_SERVER_ROOT}/velocity.toml" "${rc1_jar}" "${current_jar}"; do
  test -f "${required}"
done

cp "${SLS_FIXTURE_SERVER_ROOT}/velocity.jar" velocity.jar
cp "${SLS_FIXTURE_SERVER_ROOT}/velocity.toml" velocity.toml
mkdir plugins
cp "${rc1_jar}" plugins/sls-lite.jar

run_proxy() {
  local duration="$1"
  local log="$2"
  set +e
  timeout --signal=TERM --kill-after=10s "${duration}" \
    java -Xms128M -Xmx256M -jar velocity.jar >"${log}" 2>&1
  local status=$?
  set -e
  if [[ "${status}" -ne 0 && "${status}" -ne 124 ]]; then
    tail -n 100 "${log}"
    return "${status}"
  fi
  grep -q "SLS-LITE initialized" "${log}"
  printf '%s' "${status}"
}

rc1_status="$(run_proxy 18s /tmp/sls-rc1.log)"
test -f plugins/sls-lite/config.yml
! grep -q '^config_version:' plugins/sls-lite/config.yml

sed -i 's/total_memory_mib: 2048/total_memory_mib: 1536/' plugins/sls-lite/config.yml
printf '\n# extension-owned upgrade marker\n' >>plugins/sls-lite/config.yml
mkdir -p plugins/sls-lite/volumes/whitelists/rc2-upgrade
cp "${fixture_root}/rc2-upgrade-whitelist.json" \
  plugins/sls-lite/volumes/whitelists/rc2-upgrade/whitelist.json
cp "${fixture_root}/rc2-upgrade-whitelist.json" plugins/sls-lite/extension-custom.yml

# Capture the complete RC.1 pair before introducing any RC.2-only blueprint state. Rollback must
# restore this data directory and plugin JAR together rather than asking RC.1 to interpret RC.2.
tar -cf /tmp/sls-rc1-rollback.tar plugins/sls-lite plugins/sls-lite.jar
mkdir -p plugins/sls-lite/blueprints/rc2-test
cp "${fixture_root}/rc2-upgrade-persistent.yml" \
  plugins/sls-lite/blueprints/rc2-test/

config_before="$(sha256sum plugins/sls-lite/config.yml | cut -d' ' -f1)"
blueprint_before="$(sha256sum plugins/sls-lite/blueprints/rc2-test/rc2-upgrade-persistent.yml | cut -d' ' -f1)"
volume_before="$(sha256sum plugins/sls-lite/volumes/whitelists/rc2-upgrade/whitelist.json | cut -d' ' -f1)"
extension_before="$(sha256sum plugins/sls-lite/extension-custom.yml | cut -d' ' -f1)"

cp "${current_jar}" plugins/sls-lite.jar
rc2_status="$(run_proxy 25s /tmp/sls-rc2.log)"

config_after="$(sha256sum plugins/sls-lite/config.yml | cut -d' ' -f1)"
blueprint_after="$(sha256sum plugins/sls-lite/blueprints/rc2-test/rc2-upgrade-persistent.yml | cut -d' ' -f1)"
volume_after="$(sha256sum plugins/sls-lite/volumes/whitelists/rc2-upgrade/whitelist.json | cut -d' ' -f1)"
extension_after="$(sha256sum plugins/sls-lite/extension-custom.yml | cut -d' ' -f1)"

test "${config_before}" = "${config_after}"
test "${blueprint_before}" = "${blueprint_after}"
test "${volume_before}" = "${volume_after}"
test "${extension_before}" = "${extension_after}"
test -d plugins/sls-lite/volumes/whitelists
test ! -e plugins/sls-lite/config-reference-v2.yml
grep -q "Host configuration unversioned legacy" /tmp/sls-rc2.log
grep -q "1 blueprint(s)" /tmp/sls-rc2.log

echo "SLS_UPGRADE_RC1_EXIT=${rc1_status}"
echo "SLS_UPGRADE_RC2_EXIT=${rc2_status}"
echo "SLS_UPGRADE_CONFIG_SHA256=${config_after}"
echo "SLS_UPGRADE_BLUEPRINT_SHA256=${blueprint_after}"
echo "SLS_UPGRADE_VOLUME_SHA256=${volume_after}"
echo "SLS_UPGRADE_EXTENSION_SHA256=${extension_after}"
grep -E "Host configuration|Setup checklist|SLS-LITE initialized|blueprint.*rejected|ERROR|Exception" \
  /tmp/sls-rc2.log | tail -n 30

mv plugins/sls-lite plugins/sls-lite-rc2-observed
mv plugins/sls-lite.jar plugins/sls-lite-rc2-observed.jar
tar -xf /tmp/sls-rc1-rollback.tar
rollback_status="$(run_proxy 18s /tmp/sls-rc1-rollback.log)"
rollback_config="$(sha256sum plugins/sls-lite/config.yml | cut -d' ' -f1)"
rollback_volume="$(sha256sum plugins/sls-lite/volumes/whitelists/rc2-upgrade/whitelist.json | cut -d' ' -f1)"
rollback_extension="$(sha256sum plugins/sls-lite/extension-custom.yml | cut -d' ' -f1)"
test "${config_before}" = "${rollback_config}"
test "${volume_before}" = "${rollback_volume}"
test "${extension_before}" = "${rollback_extension}"
test ! -e plugins/sls-lite/blueprints/rc2-test/rc2-upgrade-persistent.yml
echo "SLS_ROLLBACK_RC1_EXIT=${rollback_status}"
