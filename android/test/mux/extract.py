#!/usr/bin/env python3
"""从 MP4/MKV 中提取 Annex-B 视频样本与 AAC 原始帧（模拟 Android 编码器输出），写成测试用二进制。
格式: 头 JSON 一行，之后每个样本: track(u8) key(u8) pts_us(i64) size(u32) data"""
import json, struct, subprocess, sys, re
src, out = sys.argv[1], sys.argv[2]
ts = out + '.ts'
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', src, '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy', '-f', 'mpegts', ts], check=True)
st = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-of', 'json', ts], capture_output=True, check=True).stdout)['streams']
v = next(s for s in st if s['codec_type'] == 'video'); a = next((s for s in st if s['codec_type'] == 'audio'), None)
p = subprocess.Popen(['ffprobe', '-v', 'error', '-show_packets', '-show_data', '-of', 'compact=p=0:nk=0', ts], stdout=subprocess.PIPE, text=True)
# compact 输出不含 data；改用默认格式解析
p.kill()
txt = subprocess.run(['ffprobe', '-v', 'error', '-show_packets', '-show_data', ts], capture_output=True, text=True, check=True).stdout
samples = []; csd0 = None; asc = None
for blk in txt.split('[PACKET]')[1:]:
    blk = blk.split('[/PACKET]')[0]
    kv = dict(re.findall(r'^(\w+)=(.*)$', blk, re.M))
    data = bytearray()
    for line in blk.split('data=')[1].splitlines():
        m = re.match(r'^[0-9a-f]{8}: ((?:[0-9a-f]{2,4} ?)+)', line)
        if m: data += bytes.fromhex(m.group(1).replace(' ', ''))
    data = bytes(data)
    idx = int(kv['stream_index']); vid = st[idx]['codec_type'] == 'video'
    pts = float(kv['pts_time']) if kv.get('pts_time', 'N/A') != 'N/A' else float(kv['dts_time'])
    key = 'K' in kv.get('flags', '')
    if vid:
        if csd0 is None and key: csd0 = data
    else:
        hl = 7 if data[1] & 1 else 9
        if asc is None:
            prof = (data[2] >> 6) + 1; sri = (data[2] >> 2) & 15; ch = ((data[2] & 1) << 2) | (data[3] >> 6)
            asc = ((prof << 11) | (sri << 7) | (ch << 3)).to_bytes(2, 'big')
        data = data[hl:]
    samples.append((0 if vid else 1, key, pts, data))
t0 = min(s[2] for s in samples)
hdr = {'vcodec': v['codec_name'], 'w': v['width'], 'h': v['height'], 'csd0': csd0.hex(), 'fps': v['r_frame_rate'],
       'audio': a is not None, 'sr': int(a['sample_rate']) if a else 0, 'ch': a['channels'] if a else 0, 'asc': asc.hex() if asc else ''}
with open(out, 'wb') as f:
    f.write((json.dumps(hdr) + '\n').encode())
    for tr, key, pts, d in samples:
        f.write(struct.pack('>BBqI', tr, 1 if key else 0, round((pts - t0) * 1e6), len(d))); f.write(d)
print(len(samples), 'samples', hdr['vcodec'], hdr['w'], hdr['h'])
