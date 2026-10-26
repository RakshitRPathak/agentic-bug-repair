#!/usr/bin/env bash
# Compile and run every test with nothing but a JDK (17+).   Usage: ./build.sh [--no-test]
# Some tests drive `git` and `bash`, which any CI runner and dev machine already has.
set -euo pipefail
cd "$(dirname "$0")"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac   # Windows java wants ; in a classpath
rm -rf out && mkdir -p out/main out/test
javac --release 17 -Xlint:all -d out/main $(find src/main -name '*.java')
[ -d src/main/resources ] && cp -r src/main/resources/. out/main/
if [ "${1:-}" = "--no-test" ]; then echo "compiled to out/main"; exit 0; fi
javac --release 17 -Xlint:all -cp out/main -d out/test $(find src/test -name '*.java')
classes=$(cd src/test/java && find . -name '*Tests.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
java -cp "out/main${SEP}out/test" dev.rakshit.repair.testkit.Runner $classes
