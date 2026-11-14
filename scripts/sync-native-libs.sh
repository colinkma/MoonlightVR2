#!/usr/bin/env bash
set -euo pipefail

# Rehydrate the eight large prebuilt native libraries that GitHub's Contents
# API cannot transfer inline. Pin to the exact source commit used for import.
readonly SOURCE_COMMIT="a431efc251985b228189c015e2cdbf3c314d232b"
readonly BASE_URL="https://raw.githubusercontent.com/colinkma/moonlightvr/${SOURCE_COMMIT}"

declare -A EXPECTED_BLOB_SHA=(
  ["app/src/main/jni/moonlight-core/libopus/arm64-v8a/libopus.a"]="979eba04c2d61353914426293874dce3c7ac1c61"
  ["app/src/main/jni/moonlight-core/openssl/arm64-v8a/libcrypto.a"]="dda66a90640426441f30bb6896f4c6d5c6caa8b0"
)
for relative_path in "${!EXPECTED_BLOB_SHA[@]}"; do
  destination="${relative_path}"
  temporary="${destination}.download"
  mkdir -p "$(dirname "${destination}")"

  echo "Fetching ${relative_path}"
  curl --fail --location --silent --show-error --retry 3 \
    "${BASE_URL}/${relative_path}" --output "${temporary}"

  actual_sha="$(git hash-object "${temporary}")"
  expected_sha="${EXPECTED_BLOB_SHA[$relative_path]}"
  if [[ "${actual_sha}" != "${expected_sha}" ]]; then
    rm -f "${temporary}"
    echo "::error::SHA mismatch for ${relative_path}: expected ${expected_sha}, got ${actual_sha}"
    exit 1
  fi

  mv "${temporary}" "${destination}"
done

echo "All large native libraries were restored and verified."
