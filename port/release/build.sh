#!/bin/sh
# Builds both release archives (Linux and Windows) in the container of port/release/Dockerfile:
#     port/release/build.sh   ->   port/build_release/DBT3-linux-x64.zip, DBT3-windows-x64.zip
# Needs only docker and this repository: no disc, no game data, no build on the host.
set -e
cd "$(dirname "$0")/../.."
docker build -q -t bt3-port-release:jammy port/release >/dev/null
mkdir -p port/build_release
# the repository at /src with the release's own build folder in the place of port/build; files owned by you
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/src -v "$PWD/port/build_release":/src/port/build -w /src \
    bt3-port-release:jammy sh port/release/inside.sh
echo "releases: port/build_release/"
