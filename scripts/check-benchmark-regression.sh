#!/usr/bin/env bash
set -euo pipefail

CURRENT="${BENCHMARK_CURRENT:-bluespice-benchmarks/build/results/jmh/results.json}"
BASELINE="${BENCHMARK_BASELINE:-benchmarks/baseline/jmh-baseline.json}"
THRESHOLD="${BENCHMARK_REGRESSION_THRESHOLD:-1.20}"

python3 - "$CURRENT" "$BASELINE" "$THRESHOLD" <<'PY'
import json
import pathlib
import sys

current_path = pathlib.Path(sys.argv[1])
baseline_path = pathlib.Path(sys.argv[2])
threshold = float(sys.argv[3])

if not current_path.is_file():
    raise SystemExit(f"Current JMH result file not found: {current_path}")

if not baseline_path.is_file():
    print(f"Baseline JMH result file not found: {baseline_path}")
    print("Skipping regression comparison; set BENCHMARK_BASELINE to enable threshold checks.")
    raise SystemExit(0)

def load(path):
    with path.open(encoding="utf-8") as handle:
        rows = json.load(handle)
    result = {}
    for row in rows:
        name = row["benchmark"]
        metric = row.get("primaryMetric", {})
        percentiles = metric.get("scorePercentiles") or {}
        p95 = percentiles.get("95.0") or percentiles.get("95")
        if p95 is None:
            p95 = metric.get("score")
        if p95 is not None:
            result[name] = float(p95)
    return result

current = load(current_path)
baseline = load(baseline_path)
failed = False

print("| Benchmark | Baseline p95 | Current p95 | Ratio | Status |")
print("|-----------|--------------|-------------|-------|--------|")
for name in sorted(current):
    if name not in baseline:
        print(f"| {name} | n/a | {current[name]:.6g} | n/a | new |")
        continue
    base = baseline[name]
    now = current[name]
    ratio = float("inf") if base == 0.0 and now > 0.0 else (now / base if base else 1.0)
    status = "regressed" if ratio > threshold else "ok"
    if status == "regressed":
        failed = True
    print(f"| {name} | {base:.6g} | {now:.6g} | {ratio:.3f} | {status} |")

missing = sorted(set(baseline) - set(current))
for name in missing:
    print(f"| {name} | {baseline[name]:.6g} | n/a | n/a | missing |")
    failed = True

if failed:
    raise SystemExit(f"Benchmark regression exceeded threshold {threshold:.2f}")
PY
