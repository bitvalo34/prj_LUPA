#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -o -Dmaven.repo.local=.m2-offline clean verify package
printf '\nOffline Maven build passed. Start with:\n'
printf 'java -jar target/lupa.jar --host=127.0.0.1 --port=8080 --catalog=fixture\n'
