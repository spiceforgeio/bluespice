#!/usr/bin/env bash
set -euo pipefail

VERSION="${NGSPICE_VERSION:-44}"
SOURCE_URL="${NGSPICE_SOURCE_URL:-https://downloads.sourceforge.net/project/ngspice/ng-spice-rework/old-releases/${VERSION}/ngspice-${VERSION}.tar.gz}"
SHA256="${NGSPICE_SHA256:-3865d13ab44f1f01f68c7ac0e0716984e45dce5a86d126603c26d8df30161e9b}"
PREFIX="${NGSPICE_PREFIX:-/opt/ngspice-win}"
WORK_DIR="${NGSPICE_BUILD_DIR:-/tmp/bluespice-ngspice-build-win}"
TARBALL="${WORK_DIR}/ngspice-${VERSION}.tar.gz"
SRC_DIR="${WORK_DIR}/ngspice-${VERSION}"

if [[ -f "${PREFIX}/bin/ngspice.dll" ]]; then
  echo "ngspice shared library already exists at ${PREFIX}/bin/ngspice.dll"
  exit 0
fi

mkdir -p "${WORK_DIR}"

if [[ ! -f "${TARBALL}" ]]; then
  curl -L "${SOURCE_URL}" -o "${TARBALL}"
fi

echo "${SHA256}  ${TARBALL}" | sha256sum --check -

rm -rf "${SRC_DIR}"
tar -xzf "${TARBALL}" -C "${WORK_DIR}"

cd "${SRC_DIR}"
./configure \
  --with-ngshared \
  --enable-xspice \
  --enable-openmp \
  --host=x86_64-w64-mingw32 \
  --prefix="${PREFIX}"
make -j"$(nproc)"
make install
