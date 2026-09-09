#!/usr/bin/env bash
# Compiles and runs the ORB-over-HTTP tests without Maven.
#
# The three modules are built by the GlassFish reactor in a real build, which
# takes far longer than these tests deserve and would make this workflow
# useless as a fast signal. They have no GlassFish dependencies - that is a
# deliberate property of the design - so javac and the JUnit platform launcher
# are enough.
# orb-http-glassfish is deliberately not built here. It is the one module
# with GlassFish dependencies - Grizzly, the kernel, the EJB container - and
# assembling that classpath by hand would be reimplementing Maven badly. The
# reactor job builds it properly.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out=$(mktemp -d)

fetch() {
    mvn -q dependency:get -Dartifact="$1" -Dtransitive=false
    find ~/.m2/repository -path "*${2}*" -name '*.jar' ! -name '*sources*' | sort | tail -1
}

JUPITER_API=$(fetch org.junit.jupiter:junit-jupiter-api:5.11.3 junit-jupiter-api)
JUPITER_ENGINE=$(fetch org.junit.jupiter:junit-jupiter-engine:5.11.3 junit-jupiter-engine)
PLATFORM_COMMONS=$(fetch org.junit.platform:junit-platform-commons:1.11.3 junit-platform-commons)
PLATFORM_ENGINE=$(fetch org.junit.platform:junit-platform-engine:1.11.3 junit-platform-engine)
PLATFORM_LAUNCHER=$(fetch org.junit.platform:junit-platform-launcher:1.11.3 junit-platform-launcher)
CONSOLE=$(fetch org.junit.platform:junit-platform-console-standalone:1.11.3 junit-platform-console-standalone)
EJB_API=$(fetch jakarta.ejb:jakarta.ejb-api:4.0.1 jakarta.ejb-api)
TX_API=$(fetch jakarta.transaction:jakarta.transaction-api:2.0.1 jakarta.transaction-api)

deps="$EJB_API:$TX_API"
mkdir -p "$out/classes" "$out/tests"

echo "== main =="
javac -nowarn -cp "$deps" -d "$out/classes" \
    $(find "$root"/appserver/orb/orb-http-protocol/src/main/java \
           "$root"/appserver/orb/orb-http-client/src/main/java \
           "$root"/appserver/orb/orb-http-server/src/main/java -name '*.java')

echo "== test =="
javac -nowarn -cp "$out/classes:$deps:$CONSOLE" -d "$out/tests" \
    $(find "$root"/appserver/orb/orb-http-protocol/src/test/java \
           "$root"/appserver/orb/orb-http-server/src/test/java -name '*.java')

echo "== run =="
java -jar "$CONSOLE" execute \
    --class-path "$out/classes:$out/tests:$deps" \
    --scan-class-path "$out/tests" \
    --details=summary \
    --fail-if-no-tests
