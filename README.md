# Java Avro filter optimizations

The latest two optimizations cut processing time by **11.3–32.9%** on the measured
filter, increasing throughput by **12.7–49.1%**. **Memory allocation is unchanged.**

This fork records the changes, their individual benefits and the experiments that did
not earn a place in the final implementation. The retained code is on `main`.

## Final result

The previous version already specialized short string literals. Adding faster length
decoding and predicate ordering produced these independently confirmed results:

| Distinct records | Before | After | Processing time | Throughput |
|---|---:|---:|---:|---:|
| 10,000 | 1.423970 s | 1.262957 s | **−11.3%** | **+12.7%** |
| 100,000 | 3.171451 s | 2.185744 s | **−31.1%** | **+45.1%** |
| 1,000,000 | 4.162269 s | 2.791413 s | **−32.9%** | **+49.1%** |

Every time in this README is for **300 million record evaluations**, replaying the
stated pool of distinct records. The filter is `age > 30 && country == "FR"` over raw
Avro bytes. Timings include iteration and creation of the output buffers.

**Memory allocated per record, before → after:**
10k: **1.655 → 1.655 bytes**; 100k: **1.313 → 1.313 bytes**;
1M: **2.097 → 2.097 bytes**. These changes improve execution speed without reducing allocation.

CPU usage, CPU-seconds and resident memory were not measured directly. The
reported metrics are elapsed processing time, throughput and allocated bytes.

## 1. Compare short string literals directly

**Benefit: 17.9% less time at 10k records; 5.3% less at 1M. No allocation reduction.**

Previously, comparing a field with `"FR"` used the general array-comparison routine.
The generator now emits the known length and bytes directly:

```java
length == 2 && bytes[offset] == 'F' && bytes[offset + 1] == 'R'
```

This removes general variable-length comparison work from a frequently executed path.
It applies to literals of at most eight UTF-8 bytes; longer literals use the existing
comparison. The JIT already inlined the old helper, so the gain comes from simpler
comparison code, not merely from removing a method call.

| Distinct records | Before | After | Processing time | Throughput |
|---|---:|---:|---:|---:|
| 10,000 | 1.762927 s | 1.447141 s | **−17.9%** | **+21.8%** |
| 100,000 | 3.298141 s | 3.289299 s | No convincing gain | No convincing gain |
| 1,000,000 | 4.480896 s | 4.241866 s | **−5.3%** | **+5.6%** |

Allocation stays at **1.655 B/record at 10k**, **1.313 at 100k** and **2.097 at 1M**,
before and after. No strings were allocated for these comparisons in either version.

These are repeated measurements from the earlier campaign, with three fresh JVMs per
version and pool. The baseline is the restored Java implementation. This optimization
was retained **before** the latest autoresearch campaign.

## 2. Decode small lengths without the general long decoder

**Benefit when tested alone: 11.7% less time at 10k; 10.6% less at 1M.
No allocation reduction.**

Avro encodes a small nonnegative length in one byte. Previously, every length passed
through the general `long` decoder. The new path decodes that byte with integer
operations and checks its sign and bounds immediately. Multi-byte lengths still use
the complete existing decoder.

| Distinct records | Before | After | Processing time | Throughput |
|---|---:|---:|---:|---:|
| 10,000 | 1.408550 s | 1.244215 s | **−11.7%** | **+13.2%** |
| 1,000,000 | 4.184542 s | 3.741757 s | **−10.6%** | **+11.8%** |

Allocation: **1.655 → 1.655 B/record at 10k** and **2.097 → 2.097 at 1M**.
The decoder does less work on short lengths; it creates the same objects as before.

These isolated results come from screening with three fresh JVMs per version and pool.
The baseline already includes optimization 1. The 100k pool was not measured in
isolation. Independent confirmation tested this change together with optimization 3;
those results are in the first table.

## 3. Test the short string equality before the numeric range

**Benefit when tested alone: 26.0% less time at 1M records. The 10k result is
inconclusive. No allocation reduction.**

For a conjunction such as `age > 30 && country == "FR"`, the generator now evaluates
the short string equality first. A failing country comparison avoids the numeric
comparison. All referenced fields are still read and validated before either test.

The generator swaps an AND pair when a numeric range test comes before a string
equality with a literal of at most eight UTF-8 bytes. Numeric `EQ`/`NE` and longer
literals keep the caller's order.

| Distinct records | Before | After | Processing time | Throughput |
|---|---:|---:|---:|---:|
| 10,000 | 1.408550 s | 1.435809 s | +1.9%, inconclusive | −1.9%, inconclusive |
| 1,000,000 | 4.184542 s | 3.096385 s | **−26.0%** | **+35.1%** |

Allocation: **1.655 → 1.655 B/record at 10k** and **2.097 → 2.097 at 1M**.
The gain comes from the changed comparison path, not from skipping required decoding
or constructing fewer output records.

These are isolated screening results from three fresh JVMs per version and pool,
against the baseline with optimization 1. The 100k pool was not tested in isolation.
The small-pool mean was slower, but the uncertainty does not establish a regression.
The combined implementation was subsequently confirmed faster at all three pool sizes.

## What the percentages mean

**The individual gains do not add up.** Optimization 1 uses an earlier baseline;
optimizations 2 and 3 were each screened against a version that already included it.
The first table measures the combined effect of 2 + 3 in fresh runs. We have not
measured a cumulative percentage for all three against the earliest version in one
confirmation campaign.

A 32.9% reduction in time corresponds to a 49.1% increase in throughput for the same
work: time change is `after / before − 1`, throughput change is `before / after − 1`.

No memory-saving optimization was retained. Allocation profiles are dominated by the
output arrays. The JIT already eliminated the per-record cursor allocation before
these changes, and still does afterward.

## Other experiments

| Experiment | Result |
|---|---|
| Integer comparisons instead of converting to `double` where semantics permit | No statistically resolved useful gain. Not retained. |
| Packed UTF-8 loads of 2, 4 or 8 bytes | No statistically resolved useful gain on their own. Not retained. |
| Fuse length validation and cursor advancement | Faster means, but insufficient evidence to clear the useful-gain threshold. Not retained. |
| Combine packed loads with eager boolean evaluation | No resolved additional gain over the simpler retained combination. Not retained. |

## Measurement and correctness

The final comparison used **30 fresh JVMs and 150 measured invocations**: five JVMs
per version and pool, with six warmups and five measurements each. All three gains
clear a predeclared 2% useful-improvement threshold using conservative 95% intervals
from the autoresearch evaluator. One JVM mean counts as one independent observation.

Environment: **Apple M4 Max, Temurin 25.0.3, one benchmark thread, 2 GiB heap**.
Profiling was separate from timing. These are measurements of one in-memory filter
and caller; in a separate guard with mixed short and long strings, the gain was
inconclusive.

**249 tests passed**, including malformed input, numeric rounding, Unicode, slices
and record identity checks. Required-field validation is preserved. A standalone
Java consumer also passed against the packaged JAR.

Sources for auditing or reproduction:
[short-literal measurements](bench/results/optimization/confirmed/summary.json),
[isolated experiments](bench/research/profile-guided-2026-11-18/screen-1/comparison.json),
[final confirmation](bench/research/profile-guided-2026-11-18/confirmation/comparison.json),
[full protocol and commands](bench/research/profile-guided-2026-11-18/README.md).
CPU flame graphs for the 1M pool:
[before](bench/research/profile-guided-2026-11-18/profiles/baseline/1000000/cpu.html),
[after](bench/research/profile-guided-2026-11-18/profiles/selected/1000000/cpu.html),
[difference](bench/research/profile-guided-2026-11-18/profiles/diff/1000000.html).
Download the HTML files and open them in a browser for interactive inspection.
