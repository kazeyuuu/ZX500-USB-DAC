#include <limits.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define main uac2bridge_program_main
#include "../bridge/uac2bridge.c"
#undef main

#define CHANNELS 2u
#define GUARD_WORDS 8u
#define GUARD_VALUE ((int32_t)0x5a6bc7d8)

enum pattern {
    PATTERN_RAMP,
    PATTERN_ALTERNATING,
    PATTERN_IMPULSE,
    PATTERN_RANDOM,
    PATTERN_COUNT
};

#define NATIVE_MIN (-0x800000)
#define NATIVE_MAX 0x7fffff

static uint32_t prng_next(uint32_t *state)
{
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return *state = x;
}

static int32_t random_native(uint32_t *state)
{
    uint32_t r = prng_next(state);
    int32_t v = (int32_t)(r & 0x00ffffffu);
    return (v & 0x00800000) ? (v | (int32_t)0xff000000u) : v;
}

static void fill_input(int32_t *in, unsigned frames, enum pattern pattern)
{
    int32_t lo = NATIVE_MIN, hi = NATIVE_MAX;
    uint32_t state = 0x91e10da5u;
    unsigned i;

    for (i = 0; i < frames; ++i) {
        int32_t left;
        switch (pattern) {
        case PATTERN_RAMP:
            left = (int32_t)((int64_t)lo +
                    (((int64_t)hi - lo) * i / (frames - 1)));
            break;
        case PATTERN_ALTERNATING:
            left = (i & 1) ? hi : lo;
            break;
        case PATTERN_IMPULSE:
            left = (i == frames / 2) ? hi :
                   (i == frames / 2 + 1 ? lo : 0);
            break;
        default:
            left = random_native(&state);
            break;
        }
        in[i * CHANNELS] = left;
        in[i * CHANNELS + 1] = (pattern == PATTERN_RAMP && left != lo)
            ? -left : left;
    }
}

static int guard_ok(const int32_t *base, size_t payload_words)
{
    unsigned i;
    for (i = 0; i < GUARD_WORDS; ++i) {
        if (base[i] != GUARD_VALUE ||
            base[GUARD_WORDS + payload_words + i] != GUARD_VALUE)
            return 0;
    }
    return 1;
}

static int run_case(unsigned frames, int delta, enum pattern pattern)
{
    unsigned in_frames = (unsigned)((int)frames + delta);
    size_t in_words = (size_t)in_frames * CHANNELS;
    size_t out_words = (size_t)frames * CHANNELS;
    int32_t *in_base = malloc((in_words + 2 * GUARD_WORDS) * sizeof(*in_base));
    int32_t *out_base = malloc((out_words + 2 * GUARD_WORDS) * sizeof(*out_base));
    int32_t *in, *out;
    int32_t lo = NATIVE_MIN, hi = NATIVE_MAX;
    unsigned i, c;
    int failed = 0;

    if (!in_base || !out_base) {
        free(in_base);
        free(out_base);
        return 1;
    }
    for (i = 0; i < in_words + 2 * GUARD_WORDS; ++i) in_base[i] = GUARD_VALUE;
    for (i = 0; i < out_words + 2 * GUARD_WORDS; ++i) out_base[i] = GUARD_VALUE;
    in = in_base + GUARD_WORDS;
    out = out_base + GUARD_WORDS;
    fill_input(in, in_frames, pattern);

    g_ch = CHANNELS;
    resample_correction(in, in_frames, out, frames, CHANNELS);

    for (c = 0; c < CHANNELS; ++c) {
        if (out[c] != in[c] ||
            out[(frames - 1) * CHANNELS + c] !=
            in[(in_frames - 1) * CHANNELS + c])
            failed = 1;
    }
    for (i = 0; i < out_words; ++i) {
        if (out[i] < lo || out[i] > hi)
            failed = 1;
    }
    if (pattern == PATTERN_RAMP) {
        for (i = 1; i < frames; ++i) {
            if (out[i * CHANNELS] < out[(i - 1) * CHANNELS])
                failed = 1;
        }
    }
    if (!guard_ok(in_base, in_words) || !guard_ok(out_base, out_words))
        failed = 1;

    free(in_base);
    free(out_base);
    return failed;
}

int main(void)
{
    static const unsigned rates[] = {
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
    };
    unsigned r, p;

    for (r = 0; r < sizeof(rates) / sizeof(rates[0]); ++r) {
        unsigned frames = correction_span_frames(rates[r]);
        if (frames < PERIOD || frames > 8 * PERIOD || frames % PERIOD)
            return 10;
        for (p = 0; p < PATTERN_COUNT; ++p) {
            if (run_case(frames, -1, (enum pattern)p) ||
                run_case(frames, 0, (enum pattern)p) ||
                run_case(frames, 1, (enum pattern)p)) {
                fprintf(stderr, "FAIL rate=%u pattern=%u\n", rates[r], p);
                return 20;
            }
        }
    }
    puts("resampler: 8 rates x 24-bit x 4 patterns x 3 deltas + canaries PASS");
    return 0;
}
