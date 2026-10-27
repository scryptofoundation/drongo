#!/bin/bash
# Regenerates src/main/resources/erc20/templates.json (Linux or WSL, needs node, curl, openssl, git, bwrap).
# Downloads the pinned solc and OpenZeppelin into $EVM_BUILD (default ../evm-build next to the repo), checks each
# against its published checksum, and compiles offline inside bubblewrap.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DRONGO=$(cd "$HERE/../.." && pwd)
B=${EVM_BUILD:-$(cd "$DRONGO/../.." && pwd)/evm-build}
SOLC=solc-linux-amd64-v0.8.36+commit.8a079791
SOLC_SHA256=c8d35afdddc3cd2743ee88b8f25e0fecd16e2bdd5f2120f37e52cd9cc45ae0e6
OZ=contracts-5.6.1.tgz
OZ_SHA512=Ly6SlsVJ3mj+b18W3R8gNufB7dTICT105fJhodGAGgyC2oqnBAhqSiNDJ8V8DLY05cCz81GLI0CU5vNYA1EC/w==

mkdir -p "$B/dl" "$B/bin"
[ -f "$B/dl/$SOLC" ] || curl -fsSL --retry 3 -o "$B/dl/$SOLC" "https://binaries.soliditylang.org/linux-amd64/$SOLC"
echo "$SOLC_SHA256  $B/dl/$SOLC" | sha256sum -c -
[ -f "$B/dl/$OZ" ] || curl -fsSL --retry 3 -o "$B/dl/$OZ" "https://registry.npmjs.org/@openzeppelin/contracts/-/$OZ"
[ "$(openssl dgst -sha512 -binary "$B/dl/$OZ" | base64 -w0)" = "$OZ_SHA512" ] || { echo "$OZ: sha512 mismatch"; exit 1; }
echo "$OZ: sha512 OK"

rm -rf "$B/oz" && mkdir -p "$B/oz"
tar -xzf "$B/dl/$OZ" -C "$B/oz" --no-same-owner --no-same-permissions
install -m 755 "$B/dl/$SOLC" "$B/bin/solc"

OUT="$DRONGO/src/main/resources/erc20"
mkdir -p "$OUT"
bwrap --ro-bind / / --bind "$OUT" "$OUT" --unshare-net --dev /dev --proc /proc \
    node "$HERE/generate.mjs" "$B/bin/solc" "$B/oz/package" "$OUT/templates.json"
sha256sum "$OUT/templates.json"
