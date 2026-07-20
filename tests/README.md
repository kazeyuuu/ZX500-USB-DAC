# Tests

`resampler-test.c` includes the release bridge renderer and validates all eight
sample rates at the production 24-bit format. It covers expansion, passthrough
and compression with ramps, alternating full-scale samples, impulses and
deterministic random transients. It also checks native range, endpoints,
monotonicity and allocation canaries under ASan/UBSan.

See [BUILDING.md](../BUILDING.md#bridge) for the exact build command.
