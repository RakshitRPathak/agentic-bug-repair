### Fault localization (17 bugs)

Rank of the true buggy method among the suspects. top-k = share of bugs where it is in the first k.

| Signals | top-1 | top-3 | top-5 | MRR |
|---|---|---|---|---|
| trace only (baseline) | 6% | 6% | 6% | 0.06 |
| text search only | 53% | 88% | 100% | 0.70 |
| trace + text (no graph) | 59% | 88% | 100% | 0.74 |
| call-graph spectrum only | 47% | 82% | 82% | 0.68 |
| full (graph + trace + text) | 47% | 94% | 94% | 0.69 |

dev split (7 bugs): full top-1 14%, top-3 86%; trace-only top-1 0%, top-3 0%.

test split (10 bugs): full top-1 70%, top-3 100%; trace-only top-1 10%, top-3 10%.

### Repair (17 bugs, offline mutation synthesizer, no model)

Resolved (passes every test on a clean checkout): **11 / 17 (65%)**; source identical to the original: 10. Latency of resolved runs: median 28 s, p95 50 s.

- Bugs fixable by one small edit: 11 / 12 resolved
- Bugs needing new code or a different kind of edit: 0 / 5 resolved

| Case | Kind | Resolved | Rounds | Candidates verified | Time | Fix found |
|---|---|---|---|---|---|---|
| 01-lower-bound | operator | yes (original restored) | 2 | 10 | 34 s | replace `>` with `>=` (line 25) |
| 02-two-sum-direction | operator | yes (original restored) | 1 | 4 | 16 s | replace `>` with `<` (line 31) |
| 03-trap-water | operator | yes (original restored) | 3 | 24 | 50 s | replace `+` with `-` (line 93) |
| 04-max-area-min-max | other | no | 4 | 32 | 66 s | - |
| 05-dedupe-length | other | no | 4 | 32 | 63 s | - |
| 06-unique-substring | operator | yes (original restored) | 2 | 12 | 29 s | replace `>` with `>=` (line 31) |
| 07-k-distinct | operator | yes (original restored) | 2 | 12 | 27 s | replace `>=` with `>` (line 44) |
| 08-min-subarray | operator | yes (original restored) | 1 | 4 | 13 s | replace `>` with `>=` (line 97) |
| 09-sliding-max-expiry | operator | yes (original restored) | 2 | 12 | 28 s | replace `<` with `<=` (line 118) |
| 10-sliding-max-size | operator | no | 4 | 32 | 63 s | - |
| 11-min-window-guard | other | no | 4 | 32 | 75 s | - |
| 12-token-bucket-edge | operator | yes (original restored) | 2 | 12 | 24 s | replace `>` with `>=` (line 59) |
| 13-token-bucket-cap | other | no | 4 | 32 | 52 s | - |
| 14-window-log-expiry | operator | yes (original restored) | 2 | 16 | 35 s | replace `>` with `>=` (line 41) |
| 15-window-log-limit | operator | yes (original restored) | 2 | 16 | 37 s | replace `>=` with `>` (line 42) |
| 17-lru-evict | operator | yes (differs from original) | 1 | 4 | 11 s | replace `>` with `>=` (line 69) |
| 18-lru-touch | other | no | 4 | 20 | 29 s | - |
