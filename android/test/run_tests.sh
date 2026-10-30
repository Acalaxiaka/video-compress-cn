#!/bin/bash
set -e; cd "$(dirname "$0")"; [ -d /home/box/jdk ] && export PATH=/home/box/jdk/bin:$PATH
rm -rf /tmp/vct && mkdir -p /tmp/vct
javac -d /tmp/vct ../src/com/acalaxiaka/videocompress/BitrateCalc.java BitrateCalcTest.java && java -cp /tmp/vct BitrateCalcTest
