# Tests

`resampler-test.c` includes the bridge's drift-correction renderer and validates
all eight sample rates at the 24-bit format the DAC uses. It covers expansion,
passthrough and compression with ramps, alternating full-scale samples, impulses
and deterministic random transients. It also checks native range, endpoints,
monotonicity and allocation canaries under ASan/UBSan.

Run it with:

```bash
docker/zxbuild test
```

Expected output:

```text
resampler: 8 rates x 24-bit x 4 patterns x 3 deltas + canaries PASS
```
