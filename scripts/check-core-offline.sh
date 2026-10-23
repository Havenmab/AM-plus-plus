#!/usr/bin/env bash
#
# Compile and test :core without Gradle.
#
# Why: this repository is developed on small machines (1.8 GiB RAM, 2 cores). A Gradle
# build needs ~800 MiB for the daemon plus a Kotlin daemon and gets OOM-killed, while
# :core is pure Kotlin/JVM (stdlib + org.json only) and compiles in a single ~210 MiB JVM.
# This script is a development tool: CI still owns the authoritative build.
#
#   bash scripts/check-core-offline.sh                # compile main + tests, run every test
#   bash scripts/check-core-offline.sh --compile-only # compile only (main + tests)
#   KOTLIN_XMX=1024m bash scripts/check-core-offline.sh
#
# Pitfalls this script already handles:
#  1. The test compilation must pass -Xfriend-paths=<main output>; without it every
#     internal member of :core is reported as "cannot access ... it is internal", which
#     looks like a source error but only means main and tests were treated as separate
#     modules.
#  2. The embedded compiler itself needs kotlin-reflect, kotlinx-coroutines-core,
#     annotations, kotlin-daemon-embeddable and kotlin-script-runtime on its own
#     classpath; omitting any of them fails with NoClassDefFoundError before compiling.
#  3. Every jar is resolved by its group path, not by file name alone: the Gradle cache
#     also holds org.robolectric:junit (file junit-4.17.jar), which must never replace
#     junit:junit on the test classpath.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
COMPILE_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --compile-only) COMPILE_ONLY=1 ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# The workspace toolchain lives next to the checkout (or two levels up inside a worktree).
for candidate in "$ROOT/.toolchain/env.sh" "$ROOT/../.toolchain/env.sh" "$ROOT/../../.toolchain/env.sh"; do
  if [ -f "$candidate" ]; then . "$candidate"; break; fi
done
if ! command -v java >/dev/null 2>&1; then
  echo "java not found: set JAVA_HOME or provide .toolchain/env.sh" >&2
  exit 2
fi

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
CACHE="$GRADLE_HOME/caches/modules-2"

# jar_of <group-path-glob> <file-glob> — exact artifact, never a look-alike.
jar_of() { find "$CACHE" -path "$1" -name "$2" 2>/dev/null | sort | tail -1; }

KC=$(jar_of '*kotlin-compiler-embeddable*' 'kotlin-compiler-embeddable-*.jar')
STD=$(jar_of '*org.jetbrains.kotlin/kotlin-stdlib/*' 'kotlin-stdlib-2*.jar')
REFLECT="${REFLECT_JAR:-$(jar_of '*org.jetbrains.kotlin/kotlin-reflect/*' 'kotlin-reflect-*.jar')}"
CORO="${CORO_JAR:-$(jar_of '*kotlinx-coroutines-core-jvm/*' 'kotlinx-coroutines-core-jvm-*.jar')}"
ANN="${ANNOTATIONS_JAR:-$(jar_of '*org.jetbrains/annotations/*' 'annotations-2*.jar')}"
DAEMON=$(jar_of '*kotlin-daemon-embeddable*' 'kotlin-daemon-embeddable-*.jar')
SCRIPTRT=$(jar_of '*kotlin-script-runtime*' 'kotlin-script-runtime-*.jar')
JSON=$(jar_of '*org.json/json/*' 'json-2*.jar')
JUNIT=$(jar_of '*junit/junit/*' 'junit-4*.jar')
HAM=$(jar_of '*hamcrest-core/*' 'hamcrest-core-*.jar')

MISSING=0
check_jar() {
  if [ -z "$2" ]; then
    echo "missing dependency in $CACHE: $1 (run one Gradle build once to populate the cache)" >&2
    MISSING=1
  fi
}
check_jar kotlin-compiler-embeddable "$KC"
check_jar kotlin-stdlib "$STD"
check_jar kotlin-reflect "$REFLECT"
check_jar kotlinx-coroutines-core-jvm "$CORO"
check_jar annotations "$ANN"
check_jar kotlin-daemon-embeddable "$DAEMON"
check_jar kotlin-script-runtime "$SCRIPTRT"
check_jar org.json:json "$JSON"
check_jar junit:junit "$JUNIT"
check_jar hamcrest-core "$HAM"
[ $MISSING -ne 0 ] && exit 2

COMPILER_CP="$KC:$STD:$REFLECT:$CORO:$ANN:$DAEMON:$SCRIPTRT"
OUT="${AMPP_CORE_OUT:-${TMPDIR:-/tmp}/ampp-core-offline}"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
XMX="${KOTLIN_XMX:-720m}"

compile() {
  local label="$1" log="$2" dest="$3" extra="$4" sources="$5"
  echo "== $label =="
  # shellcheck disable=SC2086
  java -Xmx"$XMX" -XX:MaxMetaspaceSize=320m -cp "$COMPILER_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -nowarn -jvm-target 17 $extra \
    -d "$dest" $sources > "$log" 2>&1
  local rc=$?
  grep -E '^(e|w): ' "$log" | head -40
  if [ $rc -ne 0 ]; then
    echo "FAILED ($label); full log: $log" >&2
    tail -20 "$log" >&2
  fi
  return $rc
}

MAIN_SRC=$(find core/src/main/kotlin -name '*.kt' | wc -l)
TEST_SRC=$(find core/src/jvmTest -name '*.kt' | wc -l)
compile ":core main ($MAIN_SRC files)" "$OUT/main.log" "$OUT/main" \
  "-classpath $STD:$JSON" "core/src/main/kotlin" || exit 1
compile ":core jvmTest ($TEST_SRC files)" "$OUT/test.log" "$OUT/test" \
  "-classpath $STD:$JSON:$JUNIT:$HAM:$OUT/main -Xfriend-paths=$OUT/main" "core/src/jvmTest" || exit 1
echo "compile: OK"

if [ $COMPILE_ONLY -eq 1 ]; then
  echo "compile-only requested; skipping tests"
  exit 0
fi

CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
if [ -z "$CLASSES" ]; then echo "no test classes compiled" >&2; exit 1; fi
COUNT=$(printf '%s\n' "$CLASSES" | wc -l)
echo "== tests ($COUNT classes) =="
# shellcheck disable=SC2086
java -Xmx"$XMX" -cp "$OUT/test:$OUT/main:$STD:$JSON:$JUNIT:$HAM" \
  org.junit.runner.JUnitCore $CLASSES > "$OUT/junit.log" 2>&1
rc=$?
tail -6 "$OUT/junit.log"
if [ $rc -ne 0 ]; then
  echo "FAILED: see $OUT/junit.log" >&2
  exit $rc
fi
echo "tests: OK ($OUT/junit.log)"
