#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/.dit/bin}"
OUT_JAR="$OUT_DIR/dit-dbms-bridge.jar"

mkdir -p "$OUT_DIR"

mvn -f "$ROOT_DIR/java/oracle-bridge/pom.xml" -DskipTests clean package
cp "$ROOT_DIR/java/oracle-bridge/target/dit-dbms-bridge.jar" "$OUT_JAR"
rm -f "$OUT_DIR/dit-oracle-bridge.jar"

echo "Built: $OUT_JAR"
