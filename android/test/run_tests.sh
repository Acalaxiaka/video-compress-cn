#!/bin/bash
set -e; cd "$(dirname "$0")"; [ -d /home/box/jdk ] && export PATH=/home/box/jdk/bin:$PATH
rm -rf /tmp/vct && mkdir -p /tmp/vct
S=../src/com/acalaxiaka/videocompress
javac -d /tmp/vct $S/BitrateCalc.java $S/Eta.java BitrateCalcTest.java EtaTest.java
java -cp /tmp/vct BitrateCalcTest && java -cp /tmp/vct EtaTest
