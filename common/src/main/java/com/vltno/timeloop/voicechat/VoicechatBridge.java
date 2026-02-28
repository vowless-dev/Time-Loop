package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.compat.VoicechatInteractionCompat;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import java.util.*;
import java.util.concurrent.*;

public class VoicechatBridge implements Bridge {

    public static VoicechatServerApi serverApi;
    public static VolumeCategory LOOP_CATEGORY;

    /** Active AudioPlayers keyed by "playerName|iter|seg|regionIndex". */
    private final Map<String, AudioPlayer> players = new ConcurrentHashMap<>();
    private final Map<String, EntityAudioChannel> entityChannels = new ConcurrentHashMap<>();
    private final Map<String, LocationalAudioChannel> locationalChannels = new ConcurrentHashMap<>();

    /**
     * Scheduled speech regions waiting to be started at the right tick.
     * Populated after decode completes; consumed by {@link #onTick()}.
     */
    private final List<ScheduledSpeechRegion> scheduledRegions =
            Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService decodePool =
            Executors.newFixedThreadPool(Math.max(2,
                    Runtime.getRuntime().availableProcessors() / 2));

    /* ───────────── INIT ───────────── */

    public static void initCategory() {
        LOOP_CATEGORY = serverApi.volumeCategoryBuilder()
                .setId("timeloop_voice")
                .setName("Time Loop Voices")
                .build();
        serverApi.registerVolumeCategory(LOOP_CATEGORY);
    }

    /* ───────────── PLAYBACK ───────────── */

    /**
     * Starts voice playback for all previous iterations of the given player.
     * <p>
     * The caller must have already called {@code sceneFile.startPlayback()} so that
     * the mocap FakePlayer entities are spawned. The {@code entities} list contains
     * those entities in scene order (one per recording/scene-element). We match
     * each non-empty audio iteration to its corresponding entity by index.
     * <p>
     * Decoded PCM is split into speech regions (contiguous non-silent sections).
     * Each region is scheduled to start its own {@link AudioPlayer} at the
     * appropriate tick so the SVC speaker icon only appears during actual speech.
     *
     * @param data     the player whose voice recordings should play
     * @param entities pre-assigned mocap FakePlayer entities in scene order
     */
    @Override
    public void onStartPlayback(PlayerData data, List<net.minecraft.world.entity.Entity> entities) {
        TimeLoop.LOOP_LOGGER.info("[Voice] onStartPlayback called for player {} with {} entities",
                data.getName(), entities.size());

        stopPlayback(data.getName());

        int currentIteration = TimeLoop.loopIteration;

        // Capture the tick counter NOW — this is the reference point for
        // tick-gated scheduling. The PCM buffer is tick-indexed from 0 (the
        // start of the loop iteration).
        final int iterationStartTick = TimeLoop.tickCounter;

        // Build a deterministic mapping: audioIndex -> entity.
        // audioIndex is the Nth iteration (in order 0..currentIteration-1) that
        // has non-empty audio. The scene has one entity per recording, in the
        // same order, so entity[audioIndex] is the correct match.
        List<Integer> audioIterations = new ArrayList<>();
        for (int iter = 0; iter < currentIteration; iter++) {
            if (!data.getAudio(iter).isEmpty()) {
                audioIterations.add(iter);
            }
        }

        for (int audioIdx = 0; audioIdx < audioIterations.size(); audioIdx++) {
            final int iter = audioIterations.get(audioIdx);
            // Assign entity by index; fall back to null if we have fewer entities
            final net.minecraft.world.entity.Entity assignedEntity =
                    (audioIdx < entities.size()) ? entities.get(audioIdx) : null;

            Map<Integer, List<TimedAudioFrame>> iterAudio = data.getAudio(iter);
            final int capturedAudioIdx = audioIdx;

            decodePool.execute(() -> {
                iterAudio.forEach((segment, frames) -> {
                    if (frames.isEmpty()) {
                        TimeLoop.LOOP_LOGGER.warn(
                                "[Voice] {} iter {} seg {} has NO frames",
                                data.getName(), iter, segment
                        );
                        return;
                    }

                    short[] pcm = decodeSegment(frames, data, iter, segment);
                    if (pcm == null || pcm.length == 0) return;

                    // Extract speech regions from the decoded PCM
                    List<SpeechRegion> regions = extractSpeechRegions(pcm);

                    TimeLoop.LOOP_LOGGER.debug(
                            "[Voice] {} speech regions for {} i{} s{}",
                            regions.size(), data.getName(), iter, segment
                    );

                    // Schedule each speech region to start at the right tick
                    TimeLoop.server.execute(() -> {
                        for (int ri = 0; ri < regions.size(); ri++) {
                            SpeechRegion region = regions.get(ri);
                            int startTick = region.startSample / SAMPLES_PER_TICK;
                            String key = key(data.getName(), iter, segment) + "|" + ri;

                            scheduledRegions.add(new ScheduledSpeechRegion(
                                    key, data, iter, segment, ri,
                                    region.pcm, assignedEntity,
                                    iterationStartTick, startTick
                            ));
                        }
                    });
                });
            });
        }
    }

    /* ───────────── DECODING ───────────── */

    /**
     * Decodes a list of timed Opus frames into a full-duration PCM buffer.
     * <p>
     * Each frame carries the server tick at which it was captured.
     * Within continuous speech (consecutive ticks with audio), frames are
     * placed <b>contiguously</b> (back-to-back) so there are no intra-speech
     * silence pops.  The tick is used only to position the <i>start</i> of
     * each speech burst; a new burst begins whenever there is a gap of one
     * or more ticks with no frames.
     * <p>
     * Gaps between speech bursts remain as zero-filled silence.
     */
    private short[] decodeSegment(List<TimedAudioFrame> frames,
                                  PlayerData data,
                                  int iter,
                                  int seg) {
        // Buffer sized to the full loop duration + 1 tick of padding
        int bufferSamples = (TimeLoop.loopLengthTicks + 1) * SAMPLES_PER_TICK;
        short[] pcm = new short[bufferSamples];

        OpusDecoder decoder = serverApi.createDecoder();
        try {
            int writeHead   = -1;   // next sample position to write at
            int prevTick    = -1;   // tick of the previous frame
            int framesPlaced = 0;

            for (TimedAudioFrame frame : frames) {
                int tickStart = frame.tick() * SAMPLES_PER_TICK;

                if (writeHead < 0 || frame.tick() > prevTick + 1) {
                    // First frame ever, or a silence gap (skipped tick) —
                    // jump the write head to this tick's start position.
                    writeHead = tickStart;
                }
                // else: same tick or next consecutive tick —
                //        keep writeHead where it is (contiguous placement).

                prevTick = frame.tick();

                // Bounds check
                if (writeHead + FRAME_SIZE > pcm.length) continue;

                short[] decoded = decoder.decode(frame.opus());
                System.arraycopy(decoded, 0, pcm, writeHead,
                        Math.min(decoded.length, FRAME_SIZE));
                writeHead += FRAME_SIZE;
                framesPlaced++;
            }

            TimeLoop.LOOP_LOGGER.debug(
                    "[Voice] Decoded {}/{} frames for {} i{} s{}",
                    framesPlaced, frames.size(), data.getName(), iter, seg
            );

            return pcm;

        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error(
                    "[Voice] Decode failed for {} i{} s{}",
                    data.getName(), iter, seg, e
            );
            return null;
        } finally {
            decoder.close();
        }
    }

    /* ───────────── VOICE DISTANCE ──────────── */

    /**
     * Returns the voice distance to use for audio channels.
     * <p>
     * Reads SVC's {@code max_voice_distance} from the server config via the
     * {@link de.maxhenkel.voicechat.api.config.ConfigAccessor} API. Falls back
     * to our own {@link TimeLoop#voiceAudioDistance} if the API is unavailable.
     *
     * @return the voice distance in blocks
     */
    private static float getVoiceDistance() {
        if (serverApi == null) return TimeLoop.voiceAudioDistance;
        try {
            return (float) serverApi.getServerConfig().getDouble("max_voice_distance", TimeLoop.voiceAudioDistance);
        } catch (Exception e) {
            return TimeLoop.voiceAudioDistance;
        }
    }

    /* ───────────── CONSTANTS ───────────── */

    // 48 kHz · 50 ms/tick = 2400 samples per tick
    private static final int SAMPLES_PER_TICK = 2400;
    // Each Opus frame = 20 ms = 960 samples
    private static final int FRAME_SIZE = 960;
    // Minimum consecutive silent frames to split speech regions
    private static final int SILENCE_GAP_FRAMES = 5; // ~100ms

    /* ───────────── SPEECH REGION EXTRACTION ───────────── */

    /** A contiguous block of non-silent PCM audio and its position in the full buffer. */
    private record SpeechRegion(int startSample, short[] pcm) {}

    /**
     * Scans a full-duration PCM buffer and extracts contiguous speech regions.
     * A speech region begins at the first non-silent frame and ends after
     * {@link #SILENCE_GAP_FRAMES} consecutive silent frames.
     */
    private List<SpeechRegion> extractSpeechRegions(short[] fullPcm) {
        List<SpeechRegion> regions = new ArrayList<>();
        int regionStart = -1;
        int silentCount = 0;
        int lastNonSilentEnd = 0;

        for (int i = 0; i + FRAME_SIZE <= fullPcm.length; i += FRAME_SIZE) {
            boolean silent = true;
            for (int j = i; j < i + FRAME_SIZE; j++) {
                if (fullPcm[j] != 0) { silent = false; break; }
            }

            if (!silent) {
                if (regionStart < 0) regionStart = i;
                silentCount = 0;
                lastNonSilentEnd = i + FRAME_SIZE;
            } else if (regionStart >= 0) {
                silentCount++;
                if (silentCount >= SILENCE_GAP_FRAMES) {
                    short[] regionPcm = new short[lastNonSilentEnd - regionStart];
                    System.arraycopy(fullPcm, regionStart, regionPcm, 0, regionPcm.length);
                    regions.add(new SpeechRegion(regionStart, regionPcm));
                    regionStart = -1;
                    silentCount = 0;
                }
            }
        }
        // Flush trailing region
        if (regionStart >= 0) {
            short[] regionPcm = new short[lastNonSilentEnd - regionStart];
            System.arraycopy(fullPcm, regionStart, regionPcm, 0, regionPcm.length);
            regions.add(new SpeechRegion(regionStart, regionPcm));
        }
        return regions;
    }

    /* ───────────── CHANNEL START ───────────── */

    /**
     * Starts an {@link EntityAudioChannel} for a single speech region.
     * The AudioPlayer feeds only the region's PCM (no silence padding),
     * so the SVC speaker icon only appears during actual speech.
     */
    private void startRegionEntityChannel(String key, short[] regionPcm,
                                          net.minecraft.world.entity.Entity mcEntity) {
        de.maxhenkel.voicechat.api.Entity svcEntity = serverApi.fromEntity(mcEntity);
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(
                UUID.randomUUID(), svcEntity
        );
        if (channel == null) {
            TimeLoop.LOOP_LOGGER.warn("[Voice] Failed to create EntityAudioChannel for key={}", key);
            return;
        }
        channel.setCategory(LOOP_CATEGORY.getId());
        channel.setDistance(getVoiceDistance());

        AudioPlayer player = createSimpleAudioPlayer(channel, regionPcm, mcEntity);
        entityChannels.put(key, channel);
        players.put(key, player);
        player.startPlaying();
    }

    /**
     * Fallback: starts a {@link LocationalAudioChannel} for a speech region
     * when no entity is available.
     */
    private void startRegionLocationalChannel(String key, short[] regionPcm,
                                              PlayerData data, int iter, int seg, int regionStartTick) {
        de.maxhenkel.voicechat.api.ServerLevel level =
                serverApi.fromServerLevel(TimeLoop.serverLevel);
        var pos = data.getPosition(iter, seg, regionStartTick);
        double px = pos != null ? pos.x : 0, py = pos != null ? pos.y : 0, pz = pos != null ? pos.z : 0;

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                UUID.randomUUID(), level, serverApi.createPosition(px, py, pz)
        );
        channel.setCategory(LOOP_CATEGORY.getId());
        channel.setDistance(getVoiceDistance());

        AudioPlayer player = createSimpleAudioPlayer(channel, regionPcm, null);
        locationalChannels.put(key, channel);
        players.put(key, player);
        player.startPlaying();
    }

    /**
     * Creates a simple AudioPlayer that feeds PCM frames sequentially until
     * exhausted. No tick-gating — the region is started at the right tick by
     * {@link #onTick()}, and the AudioPlayer runs at its native rate.
     *
     * @param channel     the SVC audio channel
     * @param regionPcm   trimmed PCM containing only the speech audio
     * @param boundEntity the mocap entity (for game event emission), or null
     */
    private AudioPlayer createSimpleAudioPlayer(AudioChannel channel, short[] regionPcm,
                                                net.minecraft.world.entity.Entity boundEntity) {
        final int[] cursor = {0};

        return serverApi.createAudioPlayer(
                channel,
                serverApi.createEncoder(),
                () -> {
                    if (cursor[0] + FRAME_SIZE > regionPcm.length) return null; // done

                    short[] out = new short[FRAME_SIZE];
                    System.arraycopy(regionPcm, cursor[0], out, 0, FRAME_SIZE);
                    cursor[0] += FRAME_SIZE;

                    if (boundEntity != null && TimeLoop.voiceInteractionEnabled && !AudioUtils.isSilent(out)) {
                        TimeLoop.server.execute(() ->
                                VoicechatInteractionCompat.emitIfLoudEnough(boundEntity, out)
                        );
                    }

                    return out;
                }
        );
    }

    /* ───────────── TICK UPDATE ───────────── */

    @Override
    public void onTick() {
        processScheduledRegions();
    }

    /**
     * Each tick, checks scheduled speech regions and starts an AudioPlayer
     * for any region whose start tick has arrived.
     */
    private void processScheduledRegions() {
        if (scheduledRegions.isEmpty()) return;

        int currentTick = TimeLoop.tickCounter;

        List<ScheduledSpeechRegion> snapshot;
        synchronized (scheduledRegions) {
            snapshot = new ArrayList<>(scheduledRegions);
        }

        for (ScheduledSpeechRegion region : snapshot) {
            int elapsed = currentTick - region.iterationStartTick;
            if (elapsed >= region.regionStartTick) {
                scheduledRegions.remove(region);
                if (region.entity != null) {
                    startRegionEntityChannel(region.key, region.pcm, region.entity);
                } else {
                    startRegionLocationalChannel(region.key, region.pcm,
                            region.data, region.iter, region.seg, region.regionStartTick);
                }
            }
        }
    }

    /* ───────────── ENTITY LOOKUP ───────────── */

    /**
     * Scans the server level for ALL mocap FakePlayer entities matching the
     * given nickname, in iteration order. Called from
     * {@link TimeLoop#startPlaybackForAllPlayers()} after the scene has been
     * started so that all entities are already spawned.
     *
     * @param nickname the display name of the mocap entities
     * @return list of matching entities in iteration order (may be empty)
     */
    public static List<net.minecraft.world.entity.Entity> findAllMocapEntities(String nickname) {
        List<net.minecraft.world.entity.Entity> result = new ArrayList<>();
        if (TimeLoop.serverLevel == null || nickname == null || TimeLoop.server == null) return result;

        Set<UUID> realPlayerUUIDs = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer sp : TimeLoop.server.getPlayerList().getPlayers()) {
            realPlayerUUIDs.add(sp.getUUID());
        }

        for (net.minecraft.world.entity.Entity entity : TimeLoop.serverLevel.getAllEntities()) {
            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                if (realPlayerUUIDs.contains(serverPlayer.getUUID())) continue;
                if (nickname.equals(serverPlayer.getGameProfile().name())) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    /* ───────────── PERSISTENCE ───────────── */

    @Override
    public CompletableFuture<Void> saveAudio() {
        TimeLoop.LOOP_LOGGER.info("[Voice] Saving all audio to disk...");
        return AudioPersistence.saveAllAudio().thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] Audio save complete.")
        );
    }

    @Override
    public CompletableFuture<Void> loadAudio() {
        TimeLoop.LOOP_LOGGER.info("[Voice] Loading audio from disk...");
        return AudioPersistence.loadAllAudio().thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] Audio load complete.")
        );
    }

    @Override
    public void shutdown() {
        stopPlayback(null);
        decodePool.shutdown();
        AudioPersistence.shutdown();
        TimeLoop.LOOP_LOGGER.info("[Voice] VoicechatBridge shut down.");
    }

    /* ───────────── STOP ───────────── */

    public void stopPlayback(String player) {
        if (player == null || player.isEmpty()) {
            players.values().forEach(AudioPlayer::stopPlaying);
            players.clear();
            entityChannels.clear();
            locationalChannels.clear();
            scheduledRegions.clear();
            return;
        }
        players.forEach((k, p) -> {
            if (k.startsWith(player + "|")) p.stopPlaying();
        });
        players.keySet().removeIf(k -> k.startsWith(player + "|"));
        entityChannels.keySet().removeIf(k -> k.startsWith(player + "|"));
        locationalChannels.keySet().removeIf(k -> k.startsWith(player + "|"));
        scheduledRegions.removeIf(r -> r.data.getName().equals(player));
    }

    /* ───────────── KEY / HELPER TYPES ───────────── */

    private static String key(String p, int i, int s) {
        return p + "|" + i + "|" + s;
    }

    /**
     * A decoded speech region waiting to be started at the right tick.
     */
    private record ScheduledSpeechRegion(
            String key, PlayerData data, int iter, int seg, int regionIndex,
            short[] pcm, net.minecraft.world.entity.Entity entity,
            int iterationStartTick, int regionStartTick
    ) {}
}
