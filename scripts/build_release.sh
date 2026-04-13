#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./scripts/build_release.sh [--label <release-label>] [--skip-tests]

Builds the Ghidra plugin zip and the complete release zip.

Options:
  --label       Release label used in generated filenames.
                Defaults to <git-sha>-<utc-timestamp>.
  --skip-tests  Skip Maven tests while packaging.
EOF
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

release_label=""
skip_tests="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --label)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --label" >&2
        usage
        exit 1
      fi
      release_label="$2"
      shift 2
      ;;
    --skip-tests)
      skip_tests="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$release_label" ]]; then
  release_label="$(git -C "$repo_root" rev-parse --short HEAD)-$(date -u +%Y%m%d-%H%M%S)"
fi

maven_args=("-B" "clean" "package" "-Drelease.label=$release_label")

if [[ "$skip_tests" == "true" ]]; then
  maven_args+=("-DskipTests")
fi

cd "$repo_root"
mvn "${maven_args[@]}"

checksum_file="target/GhydraMCP-${release_label}-SHA256SUMS.txt"
(
  cd target
  sha256sum "GhydraMCP-${release_label}.zip" "GhydraMCP-Complete-${release_label}.zip"
) > "$checksum_file"

printf 'Built release artifacts:\n'
printf '  %s\n' "$repo_root/target/GhydraMCP-${release_label}.zip"
printf '  %s\n' "$repo_root/target/GhydraMCP-Complete-${release_label}.zip"
printf '  %s\n' "$repo_root/$checksum_file"