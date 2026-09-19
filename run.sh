#!/usr/bin/env bash
# Запуск. Ключ и каталог берутся из .env или ~/.config/live-translator/config,
# задавать их в сессии терминала не нужно. Аргументы пробрасываются дальше.
set -euo pipefail
cd "$(dirname "$0")"

[ -f target/live-translator.jar ] || mvn -q package
exec java -jar target/live-translator.jar "$@"
