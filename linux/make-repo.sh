#!/bin/sh
# XDesk's apt repository from a directory of packages: make-repo.sh DEBS REPO
# (xdesk-core from build-debs.sh, xdesk-mesa's from linux-mesa.yml). Signed when XDESK_APT_KEY holds the secret key
# (armored); the public half is linux/apt/xdesk-archive.asc. Needs apt-utils (apt-ftparchive) and gpg.
set -eu
DEBS=$1 REPO=$2
SUITE=resolute
mkdir -p "$REPO/pool/main" "$REPO/dists/$SUITE/main/binary-arm64" "$REPO/dists/$SUITE/main/binary-all"
cp "$DEBS"/*.deb "$REPO/pool/main/"
cd "$REPO"
apt-ftparchive --arch arm64 packages pool > "dists/$SUITE/main/binary-arm64/Packages"
apt-ftparchive --arch all packages pool > "dists/$SUITE/main/binary-all/Packages"
for f in dists/$SUITE/main/binary-*/Packages; do gzip -9kf "$f"; done
apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=XDesk -o APT::FTPArchive::Release::Label=XDesk \
    -o APT::FTPArchive::Release::Suite=$SUITE -o APT::FTPArchive::Release::Codename=$SUITE \
    -o APT::FTPArchive::Release::Architectures="arm64 all" -o APT::FTPArchive::Release::Components=main \
    release "dists/$SUITE" > "dists/$SUITE/Release.new"
mv "dists/$SUITE/Release.new" "dists/$SUITE/Release"
if [ -n "${XDESK_APT_KEY:-}" ]; then
    export GNUPGHOME=$(mktemp -d)
    printf '%s\n' "$XDESK_APT_KEY" | gpg --batch --import
    gpg --batch --yes --clearsign -o "dists/$SUITE/InRelease" "dists/$SUITE/Release"
    gpg --batch --yes -abs -o "dists/$SUITE/Release.gpg" "dists/$SUITE/Release"
    rm -rf "$GNUPGHOME"
else
    echo "make-repo: not signed (no XDESK_APT_KEY)" >&2
fi
