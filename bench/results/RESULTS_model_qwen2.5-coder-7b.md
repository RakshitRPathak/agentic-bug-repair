### Repair (17 bugs, mutation first, then model: qwen2.5-coder:7b via http://localhost:11434/v1)

Resolved (passes every test on a clean checkout): **11 / 17 (65%)**; source identical to the original: 10. Latency of resolved runs: median 8 s, p95 18 s.

- Bugs fixable by one small edit: 11 / 12 resolved
- Bugs needing new code or a different kind of edit: 0 / 5 resolved

Model calls: 26 in total, 66226 input and 4668 output tokens (2547 input tokens per call).

| Case | Kind | Resolved | Rounds | Candidates verified | Model calls | Time | Fix found |
|---|---|---|---|---|---|---|---|
| 01-lower-bound | operator | yes (original restored) | 2 | 12 | 0 | 15 s | replace `>` with `>=` (line 25) |
| 02-two-sum-direction | operator | yes (original restored) | 1 | 4 | 0 | 4 s | replace `>` with `<` (line 31) |
| 03-trap-water | operator | yes (original restored) | 3 | 24 | 2 | 18 s | replace `+` with `-` (line 93) |
| 04-max-area-min-max | other | no | 4 | 32 | 4 | 26 s | - |
| 05-dedupe-length | other | no | 4 | 32 | 4 | 29 s | - |
| 06-unique-substring | operator | yes (original restored) | 2 | 12 | 0 | 7 s | replace `>` with `>=` (line 31) |
| 07-k-distinct | operator | yes (original restored) | 2 | 12 | 0 | 7 s | replace `>=` with `>` (line 44) |
| 08-min-subarray | operator | yes (original restored) | 1 | 4 | 0 | 4 s | replace `>` with `>=` (line 97) |
| 09-sliding-max-expiry | operator | yes (original restored) | 2 | 12 | 0 | 8 s | replace `<` with `<=` (line 118) |
| 10-sliding-max-size | operator | no | 4 | 33 | 4 | 26 s | - |
| 11-min-window-guard | other | no | 4 | 35 | 4 | 33 s | - |
| 12-token-bucket-edge | operator | yes (original restored) | 2 | 12 | 0 | 8 s | replace `>` with `>=` (line 59) |
| 13-token-bucket-cap | other | no | 4 | 33 | 4 | 28 s | - |
| 14-window-log-expiry | operator | yes (original restored) | 2 | 16 | 0 | 9 s | replace `>` with `>=` (line 41) |
| 15-window-log-limit | operator | yes (original restored) | 2 | 16 | 0 | 8 s | replace `>=` with `>` (line 42) |
| 17-lru-evict | operator | yes (differs from original) | 1 | 4 | 0 | 3 s | replace `>` with `>=` (line 69) |
| 18-lru-touch | other | no | 4 | 20 | 4 | 21 s | - |
