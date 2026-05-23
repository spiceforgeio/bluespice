#!/usr/bin/env bash
set -euo pipefail

VERSION="${NGSPICE_VERSION:-44}"
SOURCE_URL="${NGSPICE_SOURCE_URL:-https://downloads.sourceforge.net/project/ngspice/ng-spice-rework/old-releases/${VERSION}/ngspice-${VERSION}.tar.gz}"
SHA256="${NGSPICE_SHA256:-3865d13ab44f1f01f68c7ac0e0716984e45dce5a86d126603c26d8df30161e9b}"
PREFIX="${NGSPICE_PREFIX:-/opt/ngspice-mac}"
WORK_DIR="${NGSPICE_BUILD_DIR:-/tmp/bluespice-ngspice-build-mac}"
TARBALL="${WORK_DIR}/ngspice-${VERSION}.tar.gz"
SRC_DIR="${WORK_DIR}/ngspice-${VERSION}"

if [[ -f "${PREFIX}/lib/libngspice.dylib" ]]; then
  echo "ngspice shared library already exists at ${PREFIX}/lib/libngspice.dylib"
  exit 0
fi

mkdir -p "${WORK_DIR}"

if [[ ! -f "${TARBALL}" ]]; then
  curl -L "${SOURCE_URL}" -o "${TARBALL}"
fi

echo "${SHA256}  ${TARBALL}" | shasum -a 256 --check -

rm -rf "${SRC_DIR}"
tar -xzf "${TARBALL}" -C "${WORK_DIR}"

LIBOMP="${LIBOMP_PREFIX:-$(brew --prefix libomp)}"

cd "${SRC_DIR}"
./configure \
  --with-ngshared \
  --enable-xspice \
  --enable-openmp \
  CFLAGS="-I${LIBOMP}/include" \
  LDFLAGS="-L${LIBOMP}/lib" \
  --prefix="${PREFIX}"
make -j"$(sysctl -n hw.ncpu)"
make install
