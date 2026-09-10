#!/usr/bin/env bash
set -euo pipefail

output_root="$1"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk" ]]; then
  printf '%s\n' 'ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to an Android NDK.' >&2
  exit 1
fi
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
declare -A targets=(
  [arm64-v8a]='arm64:aarch64-linux-android26-clang'
  [armeabi-v7a]='arm:armv7a-linux-androideabi26-clang'
  [x86]='386:i686-linux-android26-clang'
  [x86_64]='amd64:x86_64-linux-android26-clang'
)
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  IFS=: read -r goarch compiler <<< "${targets[$abi]}"
  output="$output_root/$abi"
  mkdir -p "$output"
  (cd tailscale && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$toolchain/$compiler" go build -trimpath -buildvcs=false -buildmode=c-shared -o "$output/libdsh_tsnet.so" .)
  rm -f "$output/libdsh_tsnet.h"
done
