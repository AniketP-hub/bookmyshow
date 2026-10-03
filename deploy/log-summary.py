#!/usr/bin/env python3
"""Summarise a recorded app log (JSON lines): request counts by status/outcome, latency, errors."""
import json
import sys
from collections import Counter


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    return sorted_vals[min(len(sorted_vals) - 1, int(len(sorted_vals) * p))]


def main(path):
    status, outcome, ms = Counter(), Counter(), []
    slow, errors, first, last, total_lines, bad = [], [], None, None, 0, 0
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            total_lines += 1
            try:
                e = json.loads(line)
            except ValueError:
                bad += 1
                continue
            ts = e.get("@timestamp")
            first = first or ts
            last = ts or last
            if e.get("level") == "ERROR" and e.get("logger_name") != "access":
                errors.append((ts, e.get("message", "")[:160]))
            if e.get("logger_name") != "access":
                continue
            status[e.get("status", "?")] += 1
            outcome[e.get("outcome", "-")] += 1
            try:
                v = float(e.get("ms", 0))
                ms.append(v)
                slow.append((v, e.get("request_id", ""), e.get("path", ""), e.get("status", "")))
            except ValueError:
                pass
    reqs = sum(status.values())
    ms.sort()
    slow.sort(reverse=True)
    five = sum(n for s, n in status.items() if str(s).startswith("5"))
    print(f"log file        : {path}")
    print(f"window          : {first}  ->  {last}")
    print(f"lines           : {total_lines} ({bad} unparseable)")
    print(f"HTTP requests   : {reqs}")
    print(f"5xx responses   : {five}")
    print("\nby status")
    for s, n in sorted(status.items()):
        print(f"  {s:>4}  {n}")
    print("\nby outcome")
    for o, n in outcome.most_common():
        print(f"  {o:<22} {n}")
    print("\nlatency (ms, server-side)")
    print(f"  p50={pct(ms, .50):.1f}  p95={pct(ms, .95):.1f}  p99={pct(ms, .99):.1f}  max={ms[-1] if ms else 0:.1f}")
    print("\nslowest 5 (request_id is searchable in the raw log)")
    for v, rid, p, s in slow[:5]:
        print(f"  {v:>9.1f} ms  {s}  {p}  {rid}")
    print(f"\napplication ERROR lines: {len(errors)}")
    for ts, m in errors[:5]:
        print(f"  {ts}  {m}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: log-summary.py <app-log.jsonl>")
    main(sys.argv[1])
