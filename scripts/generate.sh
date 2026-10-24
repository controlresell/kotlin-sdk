#!/usr/bin/env bash
# Regenerates the client from the OpenAPI document of the ControlResell public API.
#
#   scripts/generate.sh [spec]   # a file or a URL; default: https://api.controlresell.com/api/public/openapi.json
#
# A free-form JSON value (the data of an event) is a kotlinx JsonElement, which the serializer reads as it is.
#
# Writes src/commonMain/kotlin/com/controlresell/sdk/generated and .spec-sha256. The document itself is not committed.
set -euo pipefail

GENERATOR_VERSION=7.26.0
SPEC="${1:-https://api.controlresell.com/api/public/openapi.json}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAR="$ROOT/build/openapi-generator-cli-$GENERATOR_VERSION.jar"
if [ ! -f "$JAR" ]; then
  mkdir -p "$ROOT/build"
  curl -fsSL -o "$JAR" \
    "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$GENERATOR_VERSION/openapi-generator-cli-$GENERATOR_VERSION.jar"
fi

case "$SPEC" in
  http://*|https://*) curl -fsSL -o "$WORK/openapi.json" "$SPEC" ;;
  *) cp "$SPEC" "$WORK/openapi.json" ;;
esac

java -jar "$JAR" generate \
  -i "$WORK/openapi.json" \
  -g kotlin \
  --library multiplatform \
  -o "$WORK/out" \
  --additional-properties=packageName=com.controlresell.sdk.generated,omitGradleWrapper=true,dateLibrary=kotlinx-datetime \
  --type-mappings=AnyType=JsonElement \
  --import-mappings=JsonElement=kotlinx.serialization.json.JsonElement \
  > "$WORK/generate.log"

TARGET="$ROOT/src/commonMain/kotlin/com/controlresell/sdk/generated"
rm -rf "$TARGET"
mkdir -p "$(dirname "$TARGET")"
cp -R "$WORK/out/src/commonMain/kotlin/com/controlresell/sdk/generated" "$TARGET"

shasum -a 256 "$WORK/openapi.json" | cut -d' ' -f1 > "$ROOT/.spec-sha256"
echo "Generated from $SPEC (sha256 $(cat "$ROOT/.spec-sha256"))"
