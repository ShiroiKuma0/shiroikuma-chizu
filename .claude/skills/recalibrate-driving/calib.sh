#!/bin/bash
# Traffic-light and turn calibration over all kept drive logs. Usage: calib.sh [-Dqueues=true]
set -e
R=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
D=$R/.scratch/drive-data
J=/usr/lib/jvm/java-21-openjdk-amd64/bin
CP=$R/OsmAnd-java/build/libs/OsmAnd-java-master-snapshot.jar:$R/OsmAnd-shared/build/libs/OsmAnd-shared-jvm-master-snapshot.jar
CP=$CP:$R/OsmAnd-java/libs/gnu-trove-osmand.jar:$R/OsmAnd-java/libs/icu4j-49_1_patched.jar
# group/artifact as the Gradle cache lays them out
for j in org.json/json commons-compress commons-logging/commons-logging com.google.code.gson/gson \
		jts-core org.jetbrains.kotlin/kotlin-stdlib/ kxml2 junidecode; do
	f=$(find ~/.gradle/caches/modules-2/files-2.1 -path "*$j*" -name '*.jar' ! -name '*sources*' ! -name '*-common*' | sort -V | tail -1)
	[ -n "$f" ] && CP=$CP:$f
done
mkdir -p "$D/classes"
"$J/javac" -nowarn -d "$D/classes" -cp "$CP" "$(dirname "$0")/Calib.java"
"$J/java" -Xmx3g "$@" -cp "$CP:$D/classes" Calib "$D"/logs/chizu-drive_*.txt "$D"/obf/*.obf 2>&1 \
	| grep -E '^(fixes|roads|junctions|signal|traffic|right|left|  stopped)'
