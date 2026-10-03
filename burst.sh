#!/usr/bin/env sh
# Usage: ./burst.sh <BASE_URL>      (needs JDK 21+)   e.g. ADMIN_TOKEN=... ./burst.sh https://seats.onrender.com
set -e
[ -n "$1" ] || { echo "usage: $0 <BASE_URL>"; exit 2; }
exec java "$(dirname "$0")/burst/Burst.java" "$1"
