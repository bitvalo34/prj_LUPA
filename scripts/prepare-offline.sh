#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf .m2-offline
mvn -Dmaven.repo.local=.m2-offline dependency:go-offline
mvn -Dmaven.repo.local=.m2-offline clean verify package
printf '\nPrepared .m2-offline and verified an online build.\n'
