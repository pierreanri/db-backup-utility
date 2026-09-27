#!/usr/bin/env bash
# Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
# No license is granted to use, copy, modify or distribute this file without permission.
# Packages a release from target/dbbackup.jar (build it first with `mvn verify`).
#
#   dev/package-release.sh <version> <output-dir>
#
# Writes to <output-dir>:
#   dbbackup.jar                the executable jar
#   dbbackup-<version>.tar.gz   dbbackup-<version>/{bin,lib,config}, README.md, CHANGELOG.md and LICENSE
#   SHA256SUMS                  checksums of the two files above
# and the release notes (the CHANGELOG.md section of the version plus installation steps) to
# target/release-notes.md.
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "usage: $0 <version> <output-dir>" >&2
    exit 2
fi
version=$1
out=$2
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
repo=https://github.com/pierreanri/db-backup-utility

jar="$root/target/dbbackup.jar"
if [ ! -f "$jar" ]; then
    echo "missing $jar: run 'mvn verify' first" >&2
    exit 1
fi
built=$(java -jar "$jar" --version 2>/dev/null | head -n 1)
if [ "$built" != "dbbackup $version" ]; then
    echo "target/dbbackup.jar reports '$built', expected 'dbbackup $version'" >&2
    exit 1
fi

notes=$(awk -v heading="## $version" '
    index($0, heading) == 1 && (length($0) == length(heading) || substr($0, length(heading) + 1, 1) == " ") { found = 1; next }
    found && /^## / { exit }
    found { print }
' "$root/CHANGELOG.md")
if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
    echo "CHANGELOG.md has no section for $version" >&2
    exit 1
fi

mkdir -p "$out"
out=$(cd -- "$out" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

dist="$work/dbbackup-$version"
mkdir -p "$dist/bin" "$dist/lib" "$dist/config"
cp "$root/bin/dbbackup" "$dist/bin/"
cp "$jar" "$dist/lib/"
cp "$root/config/dbbackup.example.yml" "$dist/config/"
cp "$root/README.md" "$root/CHANGELOG.md" "$root/LICENSE" "$dist/"
chmod 755 "$dist/bin/dbbackup"
tar -C "$work" --owner=0 --group=0 --numeric-owner -czf "$out/dbbackup-$version.tar.gz" "dbbackup-$version"

cp "$jar" "$out/dbbackup.jar"
(cd "$out" && sha256sum dbbackup.jar "dbbackup-$version.tar.gz" > SHA256SUMS)

cat > "$root/target/release-notes.md" <<EOF
$(printf '%s\n' "$notes" | sed -e '/./,$!d')

## Installation

Requires Java 21 or newer. Download \`dbbackup-$version.tar.gz\` and \`SHA256SUMS\`, then:

\`\`\`bash
sha256sum -c --ignore-missing SHA256SUMS
mkdir -p ~/.local/opt ~/.local/bin
tar -xzf dbbackup-$version.tar.gz -C ~/.local/opt
ln -sf ~/.local/opt/dbbackup-$version/bin/dbbackup ~/.local/bin/dbbackup
dbbackup --version
\`\`\`

Or run the jar directly: \`java -jar dbbackup.jar --help\`. The latest jar is always available at
$repo/releases/latest/download/dbbackup.jar.
EOF

echo "Packaged dbbackup $version in $out:"
cat "$out/SHA256SUMS"
