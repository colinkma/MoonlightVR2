#!/usr/bin/env bash
set -euo pipefail

# Rehydrate the eight large prebuilt native libraries that GitHub's Contents
# API cannot transfer inline. Pin to the exact source commit used for import.
readonly SOURCE_COMMIT="a431efc251985b228189c015e2cdbf3c314d232b"
readonly BASE_URL="https://raw.githubusercontent.com/colinkma/moonlightvr/${SOURCE_COMMIT}"

declare -A EXPECTED_BLOB_SHA=(
  ["app/src/main/jni/moonlight-core/libopus/arm64-v8a/libopus.a"]="979eba04c2d61353914426293874dce3c7ac1c61"
  ["app/src/main/jni/moonlight-core/libopus/armeabi-v7a/libopus.a"]="e5f924958ac5f127f22ee74049297d62d4f7a1e0"
  ["app/src/main/jni/moonlight-core/libopus/x86/libopus.a"]="86774da5cf23fa25ec04601566dc38364f8a4513"
  ["app/src/main/jni/moonlight-core/libopus/x86_64/libopus.a"]="1d88d0877db4480d011a1236696a6912d325844a"
  ["app/src/main/jni/moonlight-core/openssl/arm64-v8a/libcrypto.a"]="dda66a90640426441f30bb6896f4c6d5c6caa8b0"
  ["app/src/main/jni/moonlight-core/openssl/armeabi-v7a/libcrypto.a"]="62b3c2a0c5b59bdc5a558165430016a915c8a519"
  ["app/src/main/jni/moonlight-core/openssl/x86/libcrypto.a"]="4bad1835d4120eff513366d917b475ec3f77e122"
  ["app/src/main/jni/moonlight-core/openssl/x86_64/libcrypto.a"]="f7d535d9f5cbb6b97371ce516a13612889a3dc1d"
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
