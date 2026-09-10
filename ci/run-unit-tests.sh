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

# The Fory codec and what it needs to compile its generated serializers.
FORY=$(fetch org.apache.fory:fory-core:1.7.1 fory-core)
JANINO=$(fetch org.codehaus.janino:janino:3.1.12 janino/3.1.12)
COMMONS_COMPILER=$(fetch org.codehaus.janino:commons-compiler:3.1.12 commons-compiler/3.1.12)

deps="$EJB_API:$TX_API:$FORY:$JANINO:$COMMONS_COMPILER"
mkdir -p "$out/classes" "$out/tests"

echo "== main =="
javac -nowarn -cp "$deps" -d "$out/classes" \
    $(find "$root"/appserver/orb/orb-http-protocol/src/main/java \
           "$root"/appserver/orb/orb-http-client/src/main/java \
           "$root"/appserver/orb/orb-http-server/src/main/java -name '*.java')

# The Fory codec is built apart, not into the shared output. It registers
# itself through a service file and outranks everything else, so putting it
# on the class path of the other modules' tests would change what they
# discover - the modules are separate in Maven, and this has to match.
mkdir -p "$out/fory"
javac -nowarn -cp "$out/classes:$deps" -d "$out/fory" \
    $(find "$root"/appserver/orb/orb-http-codec-fory/src/main/java -name '*.java')
cp -r "$root"/appserver/orb/orb-http-codec-fory/src/main/resources/. "$out/fory/"

echo "== test =="
javac -nowarn -cp "$out/classes:$out/fory:$deps:$CONSOLE" -d "$out/tests" \
    $(find "$root"/appserver/orb/orb-http-protocol/src/test/java \
           "$root"/appserver/orb/orb-http-client/src/test/java \
           "$root"/appserver/orb/orb-http-server/src/test/java \
           "$root"/appserver/orb/orb-http-codec-fory/src/test/java -name '*.java')

# Each module's test resources go in their own directory. Two modules
# register different codecs in a file of the same name, so a flat copy would
# silently keep one of them, and the discovery tests would be checking
# something other than what Maven checks.
for m in orb-http-protocol orb-http-client orb-http-server orb-http-codec-fory; do
    if [ -d "$root/appserver/orb/$m/src/test/resources" ]; then
        mkdir -p "$out/res/$m"
        cp -r "$root/appserver/orb/$m/src/test/resources/." "$out/res/$m/"
    fi
done

echo "== run =="
# One pass per module, each with the class path that module actually has.
# Codec discovery reads the class path, so running everything together would
# not be a faster version of the Maven build - it would be a different test.
run_module() {
    local name="$1" pkg="$2" extra="${3:-}"
    echo "-- $name --"
    java -jar "$CONSOLE" execute \
        --class-path "$out/classes:$out/tests:$out/res/$name:$deps${extra:+:$extra}" \
        --select-package "$pkg" \
        --details=summary \
        --fail-if-no-tests
}

run_module orb-http-protocol   org.glassfish.orb.http.protocol
run_module orb-http-client     org.glassfish.orb.http.client
run_module orb-http-server     org.glassfish.orb.http.server
run_module orb-http-codec-fory org.glassfish.orb.http.codec.fory "$out/fory"
