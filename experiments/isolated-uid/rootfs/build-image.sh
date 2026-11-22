#!/usr/bin/env bash
# Invoked by build.sh after verifying the archive digest.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
stage=$1 lib=$2 archive=$3 compiler=$4
: "${BURROW_SRC:?set BURROW_SRC to a checkout of the Burrow fork}"
mkdir "$stage/burrow"
git -C "$BURROW_SRC" archive 8705740dc78e472d15a80c99f0e785c029b1e1d8 | tar -x -C "$stage/burrow"
patch -s -d "$stage/burrow" -p1 < "$here/burrow.patch"
cp "$here/image-path.inc" "$stage/burrow/src/"
python3 "$here/pack.py" "$archive" "$lib" "$stage/burrow/src/image-nodes.inc"
make -s -C "$stage/burrow" CC="$compiler" OUT="$lib/libburrow.so"
if [[ -n ${CHROME158_DEBS:-} ]]; then
    patch -s -d "$stage/burrow" -p1 < "$here/../chrome158/kernel-chroot.patch"
    patch -s -d "$stage/burrow" -p1 < "$here/../chrome158/installed-helper.patch"
    make -s -C "$stage/burrow" CC="$compiler" OUT="$lib/libburrow_chrome_fs.so"
fi
