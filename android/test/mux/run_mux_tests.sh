#!/bin/bash
# Mp4Writer 集成测试：ffmpeg 生成真实 HEVC/H.264(+B帧)+AAC 码流 → 提取为 Annex-B/原始 AAC 样本 → Mp4Writer 复用 → ffprobe/ffmpeg 校验
# 用法: ./run_mux_tests.sh [big]   （big: 额外生成 >4GB 文件，约需 4.5GB 空间）
set -e; cd "$(dirname "$0")"; [ -d /home/box/jdk ] && export PATH=/home/box/jdk/bin:$PATH
W=/tmp/vc_mux; rm -rf $W; mkdir -p $W/cls
javac -d $W/cls ../../src/com/acalaxiaka/videocompress/Mp4Writer.java MuxTest.java
gen() { ffmpeg -v error -y -f lavfi -i testsrc2=size=$2:rate=$3 -f lavfi -i "sine=frequency=1000:sample_rate=$4:beep_factor=4" -t $5 $6 -c:a aac -b:a 128k $W/src_$1.mp4; }
gen hevc_b 1920x1080 30 48000 20 "-c:v libx265 -preset ultrafast -x265-params bframes=3:log-level=error -b:v 3M"
gen avc_b 1280x720 30000/1001 44100 15 "-c:v libx264 -preset ultrafast -bf 2 -b:v 3M"
gen hevc_nob 1080x1920 30 48000 10 "-c:v libx265 -preset ultrafast -x265-params bframes=0:log-level=error -b:v 4M"
fail=0
check() { # name rot force64
  python3 extract.py $W/src_$1.mp4 $W/$1.bin >/dev/null
  java -cp $W/cls MuxTest $W/$1.bin $W/o_$1.mp4 $2 $3 >/dev/null
  err=$(ffmpeg -v error -i $W/o_$1.mp4 -f null - 2>&1 | head -3)
  a=$(ffmpeg -v error -i $W/src_$1.mp4 -map 0:v -fps_mode passthrough -f framemd5 - | grep -v '^#' | awk -F, '{print $6}' | md5sum)
  b=$(ffmpeg -v error -noautorotate -i $W/o_$1.mp4 -map 0:v -fps_mode passthrough -f framemd5 - | grep -v '^#' | awk -F, '{print $6}' | md5sum)
  info=$(ffprobe -v error -show_entries stream=codec_name,codec_tag_string,nb_frames,avg_frame_rate,duration,start_time -of csv=p=0 $W/o_$1.mp4 | tr '\n' ' ')
  rot=$(ffprobe -v error -show_streams $W/o_$1.mp4 | grep -m1 '^rotation=' || echo rotation=0)
  co=$(grep -ac co64 $W/o_$1.mp4 || true)
  ok=yes; [ -n "$err" ] && ok=no; [ "$a" != "$b" ] && ok=no
  echo "[$1] 解码错误:${err:-无} 视频帧逐帧一致:$([ "$a" = "$b" ] && echo 是 || echo 否) $rot co64:$([ "$co" -gt 0 ] && echo 是 || echo 否) | $info"
  [ $ok = yes ] || fail=1
}
check hevc_b 90 0
check avc_b 0 1
check hevc_nob 270 0
if [ "$1" = big ]; then
  java -Xmx1g -cp $W/cls MuxTest $W/hevc_nob.bin $W/o_big.mp4 0 0 4400000000
  ls -l $W/o_big.mp4; ffprobe -v error -show_entries stream=codec_name,nb_frames,duration:format=size,duration -of csv=p=0 $W/o_big.mp4
  grep -ac co64 <(tail -c 30000000 $W/o_big.mp4) >/dev/null && echo "co64: 是"
  ffmpeg -v error -ss 8600 -i $W/o_big.mp4 -t 60 -f null - && echo "4GB 之后的末尾 60 秒解码: 无错误"
fi
[ $fail = 0 ] && echo "ALL MUX TESTS PASSED" || { echo "MUX TESTS FAILED"; exit 1; }
