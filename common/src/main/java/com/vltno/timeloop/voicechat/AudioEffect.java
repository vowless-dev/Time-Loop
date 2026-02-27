package com.vltno.timeloop.voicechat;

import java.util.Random;

/**
 * A builder-style class for applying audio effects to PCM data.
 * <p>
 * Supports pitch shifting, reverb, and robotize effects.
 * Effects are applied in order: pitch → reverb → robot.
 * <p>
 * Usage examples:
 * <pre>
 *   // Single effect
 *   AudioEffect effect = AudioEffect.pitch(0.8f);
 *   short[] processed = effect.applyEffects(pcm);
 *
 *   // Chained effects
 *   AudioEffect effect = new AudioEffect()
 *       .changePitch(1.2f)
 *       .makeReverb(0.4f, 120, 3)
 *       .makeRobot(25f);
 *   short[] processed = effect.applyEffects(pcm);
 * </pre>
 *
 * Adapted from simple-voice-chat-recording by OmiAlien.
 */
public class AudioEffect {

    private static final int SAMPLE_RATE = 48000;

    private float pitchFactor = 1.0f;
    private boolean pitchEnabled = false;

    private float reverbDecay;
    private int reverbDelayMs;
    private int reverbRepeats;
    private boolean reverbEnabled = false;

    private float robotLfoFreq;
    private boolean robotEnabled = false;

    /* ───────────── STATIC FACTORIES ───────────── */

    /** Creates an effect with only pitch shifting enabled. */
    public static AudioEffect pitch(float pitchFactor) {
        return new AudioEffect().changePitch(pitchFactor);
    }

    /** Creates an effect with only reverb enabled. */
    public static AudioEffect reverb(float decay, int delayMs, int repeats) {
        return new AudioEffect().makeReverb(decay, delayMs, repeats);
    }

    /** Creates an effect with only robotize enabled. */
    public static AudioEffect robot(float lfoFreqHz) {
        return new AudioEffect().makeRobot(lfoFreqHz);
    }

    /** Creates an effect with random combination of effects. */
    public static AudioEffect random() {
        return new AudioEffect().addRandomEffects();
    }

    /* ───────────── BUILDER METHODS ───────────── */

    /**
     * Enable pitch shifting.
     *
     * @param pitchFactor values &lt; 1.0 lower the pitch (deeper voice),
     *                    values &gt; 1.0 raise it (higher voice).
     *                    Must be &gt; 0.
     * @return this (for chaining)
     */
    public AudioEffect changePitch(float pitchFactor) {
        if (pitchFactor <= 0) throw new IllegalArgumentException("Pitch factor must be > 0");
        this.pitchFactor = pitchFactor;
        this.pitchEnabled = true;
        return this;
    }

    /**
     * Enable reverb.
     *
     * @param decay   echo decay per repeat (0..1 exclusive). Higher = louder echoes.
     * @param delayMs delay between echoes in milliseconds. Must be &gt; 0.
     * @param repeats number of echo repetitions. Must be &gt; 0.
     * @return this (for chaining)
     */
    public AudioEffect makeReverb(float decay, int delayMs, int repeats) {
        if (decay <= 0 || decay >= 1) throw new IllegalArgumentException("Decay must be between 0 and 1 (exclusive)");
        if (delayMs <= 0) throw new IllegalArgumentException("Delay must be > 0");
        if (repeats <= 0) throw new IllegalArgumentException("Repeats must be > 0");
        this.reverbDecay = decay;
        this.reverbDelayMs = delayMs;
        this.reverbRepeats = repeats;
        this.reverbEnabled = true;
        return this;
    }

    /**
     * Enable robotize effect (amplitude modulation with a cosine LFO).
     *
     * @param lfoFreqHz LFO frequency in Hz. Must be &gt; 0.
     *                  Lower values (~5-15 Hz) give a tremolo feel,
     *                  higher values (~30-100 Hz) give a metallic/robotic sound.
     * @return this (for chaining)
     */
    public AudioEffect makeRobot(float lfoFreqHz) {
        if (lfoFreqHz <= 0) throw new IllegalArgumentException("LFO frequency must be > 0");
        this.robotEnabled = true;
        this.robotLfoFreq = lfoFreqHz;
        return this;
    }

    /**
     * Randomly enable a combination of effects with preset values.
     * Guarantees at least one effect is enabled.
     *
     * @return this (for chaining)
     */
    public AudioEffect addRandomEffects() {
        Random random = new Random();
        boolean added = false;

        if (random.nextBoolean()) {
            changePitch(0.7f);
            added = true;
        }
        if (random.nextBoolean()) {
            makeReverb(0.5f, 160, 2);
            added = true;
        }
        if (random.nextBoolean()) {
            makeRobot(30f);
            added = true;
        }

        // Guarantee at least one effect
        if (!added) {
            switch (random.nextInt(3)) {
                case 0 -> changePitch(0.7f);
                case 1 -> makeReverb(0.5f, 160, 2);
                case 2 -> makeRobot(30f);
            }
        }

        return this;
    }

    /* ───────────── APPLY ───────────── */

    /**
     * Applies all enabled effects to a copy of the PCM data.
     * Order: pitch → reverb → robot.
     *
     * @param pcm the input PCM samples (not modified)
     * @return a new array with effects applied
     */
    public short[] applyEffects(short[] pcm) {
        short[] result = pcm.clone();
        if (pitchEnabled) result = applyPitch(result, pitchFactor);
        if (reverbEnabled) result = applyReverb(result, reverbDecay, reverbDelayMs, reverbRepeats);
        if (robotEnabled) result = applyRobot(result, robotLfoFreq);
        return result;
    }

    /**
     * Returns true if any effect is enabled.
     */
    public boolean hasEffects() {
        return pitchEnabled || reverbEnabled || robotEnabled;
    }

    /* ───────────── DSP IMPLEMENTATIONS ───────────── */

    /**
     * Pitch shift via linear-interpolation resampling.
     * Changes both pitch and duration (no time-stretching).
     */
    private static short[] applyPitch(short[] pcm, float pitchFactor) {
        int newLength = (int) (pcm.length / pitchFactor);
        short[] result = new short[newLength];

        for (int i = 0; i < newLength; i++) {
            float srcIndex = i * pitchFactor;
            int index = (int) srcIndex;
            float frac = srcIndex - index;

            if (index + 1 < pcm.length) {
                result[i] = (short) ((1 - frac) * pcm[index] + frac * pcm[index + 1]);
            } else if (index < pcm.length) {
                result[i] = pcm[index];
            }
        }

        return result;
    }

    /**
     * Simple delay-line reverb with exponential decay.
     * Output may be longer than input (tail from echoes).
     */
    private static short[] applyReverb(short[] input, float decay, int delayMs, int repeats) {
        int delaySamples = (SAMPLE_RATE * delayMs) / 1000;
        int totalLength = input.length + delaySamples * repeats;
        short[] output = new short[totalLength];

        System.arraycopy(input, 0, output, 0, input.length);

        for (int r = 1; r <= repeats; r++) {
            int offset = delaySamples * r;
            float currentDecay = (float) Math.pow(decay, r);

            for (int i = 0; i < input.length; i++) {
                int delayedIndex = i + offset;
                if (delayedIndex >= output.length) break;
                int mixed = output[delayedIndex] + (int) (input[i] * currentDecay);
                output[delayedIndex] = (short) Math.max(Math.min(mixed, Short.MAX_VALUE), Short.MIN_VALUE);
            }
        }

        return output;
    }

    /**
     * Robotize via cosine LFO amplitude modulation.
     */
    private static short[] applyRobot(short[] pcm, float lfoFreqHz) {
        short[] output = new short[pcm.length];
        double lfoPhase = 0;
        double lfoIncrement = 2.0 * Math.PI * lfoFreqHz / SAMPLE_RATE;

        for (int i = 0; i < pcm.length; i++) {
            double modulator = Math.cos(lfoPhase);
            lfoPhase += lfoIncrement;
            if (lfoPhase >= 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI;

            int sample = (int) (pcm[i] * modulator);
            output[i] = (short) Math.max(Math.min(sample, Short.MAX_VALUE), Short.MIN_VALUE);
        }

        return output;
    }
}
