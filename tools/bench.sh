#!/usr/bin/env bash
# Runs one benchmark case in the dev client. Usage:
#   tools/bench.sh <label> <pack: or1024|vanilla> <shaders: on|off> [extra gradle args, e.g. -Pbaseline -PssDebug=noprobe]
# Needs run-server/bench (fixed-seed world, see docs/perf) and run/spritestream-bench/spots.txt (-Pbench=survey).
set -euo pipefail
cd "$(dirname "$0")/.."
label=$1; pack=$2; shaders=$3; shift 3

# Only one Minecraft at a time on this PC (two GPU users caused VK_ERROR_DEVICE_LOST before).
if powershell -NoProfile -Command "if (Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -like '*Minecraft*' }) { exit 0 } else { exit 1 }"; then
	echo "Another Minecraft window is open; not starting." >&2
	exit 2
fi

rm -rf "run/saves/SS Bench"
cp -r run-server/bench "run/saves/SS Bench"

case $pack in
	or1024) packs='["vanilla","file/Optimum Realism R4.2.0 1024x.zip"]' ;;
	vanilla) packs='["vanilla"]' ;;
	stratum2048) packs='["vanilla","file/Stratum 2048x.zip"]' ;;
	*) echo "unknown pack $pack" >&2; exit 1 ;;
esac
sed -i "s|^resourcePacks:.*|resourcePacks:$packs|" run/options.txt
# Stratum declares formats only up to 88; in-game it has to be force-enabled as "incompatible" too.
incompatible=$([ "$pack" = stratum2048 ] && echo '["file/Stratum 2048x.zip"]' || echo '[]')
sed -i "s|^incompatibleResourcePacks:.*|incompatibleResourcePacks:$incompatible|" run/options.txt
# Nobody touches the keyboard during a run; the default "afk" limit would cap it at 30 fps.
sed -i 's|^inactivityFpsLimit:.*|inactivityFpsLimit:"minimized"|' run/options.txt
# BACKEND=vulkan tools/bench.sh ... for the Vulkan backend (default: OpenGL, like Jayden's instance)
sed -i "s|^preferredGraphicsBackend:.*|preferredGraphicsBackend:\"${BACKEND:-opengl}\"|" run/options.txt
enable=$([ "$shaders" = on ] && echo true || echo false)
sed -i "s|^enableShaders=.*|enableShaders=$enable|" run/config/iris.properties

rm -rf "run/spritestream-bench/$label"
# NOIRIS=1 runs without Iris
iris=$([ -n "${NOIRIS:-}" ] && echo "" || echo "-Piris")
./gradlew runClient --no-daemon -q $iris -Pbench="$label" "$@" > "run/bench-$label.log" 2>&1 || true
cat "run/spritestream-bench/$label/results.csv" 2>/dev/null || { echo "no results for $label"; tail -40 "run/bench-$label.log"; }
