package com.vltno.timeloop.compat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.compat.voicechat.AudioPersistence;
import com.vltno.timeloop.compat.voicechat.AudioUtils;
import com.vltno.timeloop.compat.voicechat.TimedAudioFrame;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import java.util.concurrent.*;

/**
 * Integration with <a href="https://github.com/henkelmax/simple-voice-chat">Simple Voice Chat</a>
 * for recording and playing back voice audio during time loops.
 * <p>
 * All methods are static and guard on {@link TimeLoop#voiceChatLoaded}. When SVC
 * is not installed, every public method is a safe no-op.
 * <p>
 * Voice playback decodes Opus frames into PCM, extracts speech regions
 * (contiguous non-silent sections), and schedules per-region
 * {@link AudioPlayer AudioPlayers} so the SVC speaker icon only appears
 * during actual speech.
 */
public final class VoicechatImpl {

    private VoicechatImpl() {}

    public static VoicechatServerApi serverApi;
    public static VolumeCategory LOOP_CATEGORY;

    /** Active AudioPlayers keyed by "playerName|iter|seg|regionIndex". */
    private static final Map<String, AudioPlayer> players = new ConcurrentHashMap<>();

    /**
     * Scheduled speech regions waiting to be started at the right tick.
     * Populated after decode completes; consumed by {@link #onTick()}.
     */
    private static final List<ScheduledSpeechRegion> scheduledRegions =
            Collections.synchronizedList(new ArrayList<>());

    private static volatile ExecutorService decodePool = createDecodePool();

    private static ExecutorService createDecodePool() {
        return Executors.newFixedThreadPool(Math.max(2,
                Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "TimeLoop-VoiceDecode");
                    t.setDaemon(true);
                    return t;
                });
    }

    private static ExecutorService getDecodePool() {
        ExecutorService pool = decodePool;
        if (pool == null || pool.isShutdown()) {
            synchronized (VoicechatImpl.class) {
                pool = decodePool;
                if (pool == null || pool.isShutdown()) {
                    pool = createDecodePool();
                    decodePool = pool;
                }
            }
        }
        return pool;
    }

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
     * those entities <b>in scene order</b> (sorted by entity ID) - one per
     * recording/scene-element. When a player changes dimension mid-iteration,
     * mocap's split-recording creates multiple scene elements (and thus multiple
     * entities) for that single iteration.
     * <p>
     * Voice audio is keyed by {@code (iteration, segment)}. Segment 0 is the initial
     * recording; segment 1 is the first split (after a dimension change), etc.
     * <p>
     * The {@code sceneElementNames} list provides the definitive element ordering
     * (e.g. ["dev_loop0", "dev_loop1", "dev_loop1_2", "dev_loop2"]). By counting
     * how many elements share the same iteration number we know how many entities
     * each iteration consumes, and we can assign the correct entity to each
     * {@code (iteration, segment)} pair.
     * <p>
     * Decoded PCM is split into speech regions (contiguous non-silent sections).
     * Each region is scheduled to start its own {@link AudioPlayer} at the
     * appropriate tick so the SVC speaker icon only appears during actual speech.
     *
     * @param data              the player whose voice recordings should play
     * @param entities          pre-assigned mocap FakePlayer entities in scene order
     * @param sceneElementNames ordered list of scene element names
     */
    public static void onStartPlayback(PlayerData data, List<net.minecraft.world.entity.Entity> entities,
                                List<String> sceneElementNames) {
        if (!TimeLoop.voiceChatLoaded) return;
        TimeLoop.LOOP_LOGGER.info("[Voice] onStartPlayback called for player {} with {} entities, {} scene elements",
                data.getName(), entities.size(), sceneElementNames.size());

        stopPlayback(data.getName());

        int currentIteration = TimeLoop.loopIteration;

        // Capture the tick counter NOW - this is the reference point for
        // tick-gated scheduling. The PCM buffer is tick-indexed from 0 (the
        // start of the loop iteration).
        final int iterationStartTick = TimeLoop.tickCounter;

        // Build a per-iteration element count from scene element names.
        // Names follow the pattern: "name_loopN" (primary) or "name_loopN_S" (split).
        // Elements for the same iteration have the same loopN part.
        // We count consecutive elements sharing an iteration to determine how many
        // entities that iteration consumes.
        //
        // Example: ["dev_loop0", "dev_loop1", "dev_loop1_2", "dev_loop2"]
        //   → iter 0: 1 element, iter 1: 2 elements, iter 2: 1 element
        List<Integer> elementsPerIteration = new ArrayList<>();
        {
            int idx = 0;
            while (idx < sceneElementNames.size()) {
                String name = sceneElementNames.get(idx);
                int loopNum = parseLoopNumber(name);
                int count = 1;
                // Count subsequent elements with the same loop number
                while (idx + count < sceneElementNames.size()
                        && parseLoopNumber(sceneElementNames.get(idx + count)) == loopNum) {
                    count++;
                }
                elementsPerIteration.add(count);
                idx += count;
            }
        }

        TimeLoop.LOOP_LOGGER.debug("[Voice] Elements per iteration: {}", elementsPerIteration);

        // Walk the entities list, assigning each (iter, segment) to its entity.
        // entityIndex tracks the running position.
        record SegmentEntity(int iter, int seg, net.minecraft.world.entity.Entity entity) {}
        List<SegmentEntity> segmentEntities = new ArrayList<>();
        int entityIndex = 0;

        for (int iter = 0; iter < elementsPerIteration.size() && iter < currentIteration; iter++) {
            int elemCount = elementsPerIteration.get(iter);
            Map<Integer, List<TimedAudioFrame>> iterAudio = data.getAudio(iter);

            for (int s = 0; s < elemCount; s++) {
                net.minecraft.world.entity.Entity entity =
                        (entityIndex < entities.size()) ? entities.get(entityIndex) : null;
                // Map this segment to its entity if we have audio for it
                if (iterAudio.containsKey(s)) {
                    segmentEntities.add(new SegmentEntity(iter, s, entity));
                }
                entityIndex++;
            }
        }

        TimeLoop.LOOP_LOGGER.debug(
                "[Voice] Built {} segment-entity mappings for {} ({} entities, entityIndex={})",
                segmentEntities.size(), data.getName(), entities.size(), entityIndex
        );

        // Now decode and schedule each segment with its correct entity
        for (SegmentEntity se : segmentEntities) {
            final int iter = se.iter;
            final int seg = se.seg;
            final net.minecraft.world.entity.Entity assignedEntity = se.entity;

            List<TimedAudioFrame> frames = data.getAudio(iter).get(seg);
            if (frames == null || frames.isEmpty()) continue;

            getDecodePool().execute(() -> {
                short[] pcm = decodeSegment(frames, data, iter, seg);
                if (pcm == null || pcm.length == 0) return;

                // Extract speech regions from the decoded PCM
                List<SpeechRegion> regions = extractSpeechRegions(pcm);

                TimeLoop.LOOP_LOGGER.debug(
                        "[Voice] {} speech regions for {} i{} s{} -> entity {}",
                        regions.size(), data.getName(), iter, seg,
                        assignedEntity != null ? assignedEntity.level().dimension().location() : "null"
                );

                // Schedule each speech region to start at the right tick
                TimeLoop.server.execute(() -> {
                    for (int ri = 0; ri < regions.size(); ri++) {
                        SpeechRegion region = regions.get(ri);
                        int startTick = region.startSample / SAMPLES_PER_TICK;
                        String key = key(data.getName(), iter, seg) + "|" + ri;

                        scheduledRegions.add(new ScheduledSpeechRegion(
                                key, data, iter, seg, ri,
                                region.pcm, assignedEntity,
                                iterationStartTick, startTick
                        ));
                    }
                });
            });
        }
    }

    /**
     * Extracts the loop iteration number from a scene element name.
     * Names follow the pattern "name_loopN" or "name_loopN_S".
     * Returns -1 if the name cannot be parsed.
     */
    private static int parseLoopNumber(String elementName) {
        String[] parts = elementName.split("_");
        for (String part : parts) {
            if (part.startsWith("loop")) {
                try {
                    return Integer.parseInt(part.substring(4));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
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
    private static short[] decodeSegment(List<TimedAudioFrame> frames,
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
                    // First frame ever, or a silence gap (skipped tick) -
                    // jump the write head to this tick's start position.
                    writeHead = tickStart;
                }
                // else: same tick or next consecutive tick -
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
    private static List<SpeechRegion> extractSpeechRegions(short[] fullPcm) {
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
    private static void startRegionEntityChannel(String key, short[] regionPcm,
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
        players.put(key, player);
        player.startPlaying();
    }

    /**
     * Starts a {@link LocationalAudioChannel} for a speech region.
     * Used as a fallback when no entity is available, or when
     * {@link TimeLoop#showPlayerVoiceIcon} is {@code false} (to play audio
     * at the entity's position without showing the SVC speaker icon).
     *
     * @param key             the unique key for this region
     * @param regionPcm       trimmed PCM containing only the speech audio
     * @param data            the player data (used for position lookup when entity is null)
     * @param iter            the iteration index
     * @param seg             the segment index
     * @param regionStartTick the start tick of this region within the iteration
     * @param boundEntity     the mocap entity (for position + game event emission), or null
     */
    private static void startRegionLocationalChannel(String key, short[] regionPcm,
                                              PlayerData data, int iter, int seg,
                                              int regionStartTick,
                                              net.minecraft.world.entity.Entity boundEntity) {
        // Resolve the level from the bound entity's dimension (it may be in the
        // Nether/End if the recording was split across dimensions). Fall back to
        // the overworld if no entity is available.
        ServerLevel mcLevel = (boundEntity != null && boundEntity.level() instanceof ServerLevel sl)
                ? sl : TimeLoop.serverLevel;
        de.maxhenkel.voicechat.api.ServerLevel level =
                serverApi.fromServerLevel(mcLevel);

        double px, py, pz;
        if (boundEntity != null) {
            px = boundEntity.getX();
            py = boundEntity.getY();
            pz = boundEntity.getZ();
        } else {
            var pos = data.getPosition(iter, seg, regionStartTick);
            px = pos != null ? pos.x : 0;
            py = pos != null ? pos.y : 0;
            pz = pos != null ? pos.z : 0;
        }

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                UUID.randomUUID(), level, serverApi.createPosition(px, py, pz)
        );
        channel.setCategory(LOOP_CATEGORY.getId());
        channel.setDistance(getVoiceDistance());

        AudioPlayer player = createSimpleAudioPlayer(channel, regionPcm, boundEntity);
        players.put(key, player);
        player.startPlaying();
    }

    /**
     * Creates a simple AudioPlayer that feeds PCM frames sequentially until
     * exhausted. No tick-gating - the region is started at the right tick by
     * {@link #onTick()}, and the AudioPlayer runs at its native rate.
     *
     * @param channel     the SVC audio channel
     * @param regionPcm   trimmed PCM containing only the speech audio
     * @param boundEntity the mocap entity (for game event emission), or null
     */
    private static AudioPlayer createSimpleAudioPlayer(AudioChannel channel, short[] regionPcm,
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

                    if (boundEntity != null && TimeLoop.voiceInteractionEnabled
                            && AudioUtils.isAboveThreshold(out, TimeLoop.voiceInteractionThresholdDb)) {
                        TimeLoop.server.execute(() ->
                                VoicechatInteractionCompat.emitVoiceEvent(boundEntity)
                        );
                    }

                    return out;
                }
        );
    }

    /* ───────────── TICK UPDATE ───────────── */

    /**
     * Processes scheduled speech regions. Call once per server tick while looping.
     * No-op when SVC is not loaded.
     */
    public static void onTick() {
        if (!TimeLoop.voiceChatLoaded) return;
        processScheduledRegions();
    }

    /**
     * Each tick, checks scheduled speech regions and starts an AudioPlayer
     * for any region whose start tick has arrived.
     */
    private static void processScheduledRegions() {
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
                if (region.entity != null && TimeLoop.showPlayerVoiceIcon) {
                    // Entity channel - audio follows entity, speaker icon visible
                    startRegionEntityChannel(region.key, region.pcm, region.entity);
                } else {
                    // Locational channel - no speaker icon; positioned at entity
                    // (or fallback position if entity is null)
                    startRegionLocationalChannel(region.key, region.pcm,
                            region.data, region.iter, region.seg,
                            region.regionStartTick, region.entity);
                }
            }
        }
    }

    /* ───────────── ENTITY LOOKUP ───────────── */

    /**
     * Scans <b>all loaded server levels</b> for mocap FakePlayer entities matching
     * the given nickname. When dimension-split recordings are used, mocap spawns
     * FakePlayer entities in the dimension the recording was made in (e.g. Nether,
     * End), so scanning only the overworld would miss them.
     * <p>
     * The returned list is sorted by <b>entity ID</b> to guarantee scene order.
     * Mocap creates entities sequentially during {@code ScenePlayback.start()},
     * iterating scene elements in order. Each call to {@code level.addNewPlayer()}
     * assigns an incrementing entity ID, so sorting by ID recovers the scene
     * element order even when entities are spread across multiple dimensions.
     * <p>
     * Called from {@link TimeLoop#startPlaybackForAllPlayers()} after the scene
     * has been started so that all entities are already spawned.
     *
     * @param nickname the display name of the mocap entities
     * @return list of matching entities in scene order (may be empty)
     */
    public static List<net.minecraft.world.entity.Entity> findAllMocapEntities(String nickname) {
        List<net.minecraft.world.entity.Entity> result = new ArrayList<>();
        if (nickname == null || TimeLoop.server == null) return result;

        Set<UUID> realPlayerUUIDs = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer sp : TimeLoop.server.getPlayerList().getPlayers()) {
            realPlayerUUIDs.add(sp.getUUID());
        }

        // Scan ALL dimensions - mocap spawns FakePlayer entities in the dimension
        // the recording was made in (assigned via dimensionId in RecordingData).
        for (ServerLevel level : TimeLoop.server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    if (realPlayerUUIDs.contains(serverPlayer.getUUID())) continue;
                    if (nickname.equals(serverPlayer.getGameProfile().name())) {
                        result.add(entity);
                    }
                }
            }
        }

        // Sort by entity ID to recover scene-element order. Mocap creates
        // FakePlayer entities sequentially, so lower ID = earlier scene element.
        result.sort(Comparator.comparingInt(net.minecraft.world.entity.Entity::getId));

        return result;
    }

    /* ───────────── PERSISTENCE ───────────── */

    /** Saves all voice data to disk. No-op when SVC is not loaded. */
    public static CompletableFuture<Void> saveAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeLoop.LOOP_LOGGER.info("[Voice] Saving all audio to disk...");
        return AudioPersistence.saveAllAudio().thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] Audio save complete.")
        );
    }

    /** Saves voice data for a single player to disk. No-op when SVC is not loaded. */
    public static CompletableFuture<Void> savePlayerAudio(PlayerData playerData) {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeLoop.LOOP_LOGGER.info("[Voice] Saving audio for player {}...", playerData.getName());
        return AudioPersistence.savePlayerAudio(playerData).thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] Audio save complete for {}.", playerData.getName())
        );
    }

    /** Loads all voice data from disk into memory. No-op when SVC is not loaded. */
    public static CompletableFuture<Void> loadAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeLoop.LOOP_LOGGER.info("[Voice] Loading audio from disk...");
        return AudioPersistence.loadAllAudio().thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] Audio load complete.")
        );
    }

    /**
     * Deletes all voice audio from disk and clears in-memory audio for all players.
     * No-op when SVC is not loaded.
     */
    public static CompletableFuture<Void> deleteAllAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeLoop.LOOP_LOGGER.info("[Voice] Deleting all voice audio...");
        return AudioPersistence.deleteAllAudio().thenRun(() ->
                TimeLoop.LOOP_LOGGER.info("[Voice] All voice audio deleted.")
        );
    }

    /** Cleans up thread pools and resources. Call on server stop. No-op when SVC is not loaded. */
    public static void shutdown() {
        if (!TimeLoop.voiceChatLoaded) return;
        stopPlayback(null);
        ExecutorService pool = decodePool;
        if (pool != null) pool.shutdown();
        AudioPersistence.shutdown();
        TimeLoop.LOOP_LOGGER.info("[Voice] VoicechatImpl shut down.");
    }

    /* ───────────── STOP ───────────── */

    /**
     * Stops voice playback for the given player, or all playback if
     * {@code player} is {@code null} or empty. No-op when SVC is not loaded.
     */
    public static void stopPlayback(String player) {
        if (!TimeLoop.voiceChatLoaded) return;
        if (player == null || player.isEmpty()) {
            players.values().forEach(AudioPlayer::stopPlaying);
            players.clear();
            scheduledRegions.clear();
            return;
        }
        players.forEach((k, p) -> {
            if (k.startsWith(player + "|")) p.stopPlaying();
        });
        players.keySet().removeIf(k -> k.startsWith(player + "|"));
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
