#!/usr/bin/env python3
"""Print tracking diagnostics (files/diagnostics/track_*.jsonl) for a video time window.

Usage: diag_window.py <track.jsonl> <video_epoch_ms> <from_s> <to_s>

Columns: video time, decision, raw accuracy/speed/bearing(+acc), engine heading/sigma/confident,
yaw rate, speed, stationary flag, and the course of the engine positions +-3 fixes (what the
cursor visibly did), so a frame can be checked against what the engine believed.
"""
import json
import math
import sys


def main():
    path, epoch, t0, t1 = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
    fixes = [json.loads(l) for l in open(path) if l.startswith('{"k":"fix"')]
    print("t_video  decision              acc   spd   brg/bacc     h/hs/hc        w      v    st  disp")
    for i, x in enumerate(fixes):
        t = (x["tu"] - epoch) / 1000.0
        if not (t0 <= t <= t1):
            continue
        s = x.get("s") or {}
        disp = ""
        if s:
            a = (fixes[max(0, i - 3)].get("s") or s)
            b = (fixes[min(len(fixes) - 1, i + 3)].get("s") or s)
            k = math.cos(math.radians(s["lat"]))
            de = (b["lng"] - a["lng"]) * k * 111320.0
            dn = (b["lat"] - a["lat"]) * 111320.0
            if math.hypot(de, dn) >= 2.0:
                disp = "%3.0f" % (math.degrees(math.atan2(de, dn)) % 360)
        print("%7.1f  %-20s %5s %5s %6s/%-5s %6s/%-5s/%s %6s %6s %3s  %s" % (
            t, x.get("d"), x.get("acc"), x.get("spd"), x.get("brg"), x.get("bacc"),
            s.get("h"), s.get("hs"), s.get("hc"), s.get("w"), s.get("v"), s.get("st"), disp))


if __name__ == "__main__":
    main()
