#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY="https://github.com/OffchainLabs/hashtree.git"
readonly COMMIT="30497cff98a06362eadde897202634f91d504fd8"
readonly OUTPUT_DIR="${1:?output directory is required}"
readonly SOURCE_DIR="${OUTPUT_DIR}/source"
readonly UPSTREAM_BUILD_DIR="${OUTPUT_DIR}/upstream"

mkdir -p "${OUTPUT_DIR}"
if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  git init "${SOURCE_DIR}"
  git -C "${SOURCE_DIR}" remote add origin "${REPOSITORY}"
fi

git -C "${SOURCE_DIR}" fetch --depth=1 origin "${COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach FETCH_HEAD
test "$(git -C "${SOURCE_DIR}" rev-parse HEAD)" = "${COMMIT}"

readonly ARCHIVE="${UPSTREAM_BUILD_DIR}/lib/libhashtree.a"
make -C "${SOURCE_DIR}/src" clean OUT_DIR="${UPSTREAM_BUILD_DIR}"
make -C "${SOURCE_DIR}/src" \
  OUT_DIR="${UPSTREAM_BUILD_DIR}" \
  CFLAGS="-g -Wall -Werror -O3 -fPIC" \
  ASFLAGS="-g -fPIC" \
  "${ARCHIVE}"

case "$(uname -s)" in
  Darwin)
    cc -dynamiclib -Wl,-force_load,"${ARCHIVE}" \
      -o "${OUTPUT_DIR}/libhashtree.dylib"
    ;;
  Linux)
    cc -shared -Wl,--whole-archive "${ARCHIVE}" -Wl,--no-whole-archive \
      -o "${OUTPUT_DIR}/libhashtree.so"
    ;;
  *)
    echo "Unsupported prototype host: $(uname -s)" >&2
    exit 1
    ;;
esac
