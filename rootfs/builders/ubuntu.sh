#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: ubuntu.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64 arm i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping ubuntu ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) DEB_ARCH="arm64"; UBUNTU_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64"; UBUNTU_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf"; UBUNTU_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
esac

if [[ "$DEB_ARCH" == "armhf" || "$DEB_ARCH" == "arm64" ]]; then
  MIRROR="http://ports.ubuntu.com/ubuntu-ports/"
else
  MIRROR="http://archive.ubuntu.com/ubuntu/"
fi

if [[ "$ARCH" == "i686" ]]; then
  sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
    --include=bash,curl,wget,sudo,procps,vim-tiny,less,openssl,ca-certificates,perl-base,adduser,libpam-runtime,locales \
    resolute "$ROOTFS" "$MIRROR"
else
  wget --tries=3 "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04-base-${UBUNTU_ARCH}.tar.gz" -O "/tmp/ubuntu-base-${ARCH}.tar.gz"
  sudo tar xzf "/tmp/ubuntu-base-${ARCH}.tar.gz" -C "$ROOTFS"
  rm -f "/tmp/ubuntu-base-${ARCH}.tar.gz"
fi

sudo rm -f "${ROOTFS}/etc/apt/sources.list.d/ubuntu.sources"
sudo tee "${ROOTFS}/etc/apt/sources.list" > /dev/null <<EOF
deb ${MIRROR} resolute main restricted universe multiverse
deb ${MIRROR} resolute-updates main restricted universe multiverse
deb ${MIRROR} resolute-security main restricted universe multiverse
EOF

echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null

# Keep every future apt run small: no recommends/suggests, no package
# translations (33 MB of Translation-en), no leftover .deb archives and no
# pkgcache.bin/srcpkgcache.bin (109 MB combined in the stock image).
# This only affects apt metadata - no package or tool is removed.
sudo tee "${ROOTFS}/etc/apt/apt.conf.d/99tf-slim" > /dev/null <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
APT::Keep-Downloaded-Packages "false";
APT::Update::Post-Invoke { "rm -f /var/cache/apt/pkgcache.bin /var/cache/apt/srcpkgcache.bin || true"; };
DPkg::Post-Invoke { "rm -f /var/cache/apt/pkgcache.bin /var/cache/apt/srcpkgcache.bin || true"; };
EOF

sudo chroot "$ROOTFS" /bin/bash -c "apt-get update -o APT::Sandbox::User=root && apt-get install -y --no-install-recommends bash curl wget sudo procps vim-tiny less openssl ca-certificates perl-base"

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

# --- Image slimming ------------------------------------------------------
# 150 MB of /var/lib/apt/lists, 109 MB of apt binary caches and 13 MB of
# cached .deb files are pure metadata: "apt-get update" recreates whatever
# is needed on demand (the app's first-start script runs it before any
# install), so dropping them here cuts the image from ~405 MB to ~110 MB.
sudo chroot "$ROOTFS" /bin/bash -c "apt-get clean -o APT::Sandbox::User=root" || true
sudo rm -rf "${ROOTFS}/var/lib/apt/lists"/*
sudo rm -f  "${ROOTFS}/var/cache/apt/archives"/*.deb
sudo rm -f  "${ROOTFS}/var/cache/apt"/*.bin
# Documentation of installed packages: man-db is not even present here.
sudo rm -rf "${ROOTFS}/usr/share/doc"/* "${ROOTFS}/usr/share/man"/* \
            "${ROOTFS}/usr/share/info"/* "${ROOTFS}/usr/share/lintian"/* \
            "${ROOTFS}/usr/share/bug"/*
# Build/runtime leftovers that nothing reads at boot.
sudo rm -rf "${ROOTFS}/var/log"/* "${ROOTFS}/var/tmp"/* "${ROOTFS}/tmp"/*
sudo rm -f  "${ROOTFS}/var/cache/debconf"/*.old 2>/dev/null || true
# Keep the metadata directory itself - apt refuses to run without it.
sudo mkdir -p "${ROOTFS}/var/lib/apt/lists/partial" "${ROOTFS}/var/cache/apt/archives/partial"
sudo touch "${ROOTFS}/var/lib/apt/lists/lock" "${ROOTFS}/var/cache/apt/archives/lock"
# ------------------------------------------------------------------------

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
