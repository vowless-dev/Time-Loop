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

    private final Map<String, AudioPlayer> players = new ConcurrentHashMap<>();
    private final Map<String, EntityAudioChannel> entityChannels = new ConcurrentHashMap<>();
    private final Map<String, LocationalAudioChannel> locationalChannels = new ConcurrentHashMap<>();

    /**
     * Decoded PCM buffers waiting for the mocap entity to appear.
     * After the mocap playback command is issued, the FakePlayer entity may not
     * exist for a few ticks. We queue the decoded audio here and check each
     * tick in {@link #processPendingChannels()} until the entity is found.
     */
    private final List<PendingChannel> pendingChannels =
            Collections.synchronizedList(new ArrayList<>());

    /** Maximum ticks to wait for a mocap entity before falling back to locational audio. */
    private static final int MAX_ENTITY_WAIT_TICKS = 40; // 2 seconds

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

    public void onStartPlayback(PlayerData data) {
        TimeLoop.LOOP_LOGGER.info("[Voice] onStartPlayback called for player {}", data.getName());

        stopPlayback(data.getName());

        int currentIteration = TimeLoop.loopIteration;

        // Capture the tick counter NOW — this is the reference point for the
        // tick-gated audio player. The PCM buffer is tick-indexed from 0 (the
        // start of the loop iteration), so we need to know what tickCounter
        // corresponds to tick 0 of the iteration.
        final int iterationStartTick = TimeLoop.tickCounter;

        // Play back audio from ALL previous iterations, not just the current one
        for (int iter = 0; iter < currentIteration; iter++) {
            Map<Integer, List<TimedAudioFrame>> iterAudio = data.getAudio(iter);
            if (iterAudio.isEmpty()) continue;

            final int capturedIter = iter;
            decodePool.execute(() -> {
                iterAudio.forEach((segment, frames) -> {
                    if (frames.isEmpty()) {
                        TimeLoop.LOOP_LOGGER.warn(
                                "[Voice] {} iter {} seg {} has NO frames",
                                data.getName(), capturedIter, segment
                        );
                        return;
                    }

                    short[] pcm = decodeSegment(frames, data, capturedIter, segment);
                    if (pcm == null || pcm.length == 0) return;

                    // Schedule channel creation on the main server thread for thread safety
                    TimeLoop.server.execute(() ->
                            enqueueOrStartChannel(data, capturedIter, segment, pcm, iterationStartTick)
                    );
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

            TimeLoop.LOOP_LOGGER.info(
                    "[Voice] Decoded {} / {} frames into {} sample buffer for {} i{} s{}",
                    framesPlaced, frames.size(), pcm.length, data.getName(), iter, seg
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

    /* ───────────── CHANNEL START ───────────── */

    // 48 kHz · 50 ms/tick = 2400 samples per tick
    private static final int SAMPLES_PER_TICK = 2400;
    // Each Opus frame = 20 ms = 960 samples
    private static final int FRAME_SIZE = 960;
    // Allow audio to be this many ticks ahead of the server tick to avoid
    // stutter from timing jitter between the audio thread and server thread.
    private static final int TICK_LEAD = 2;

    /**
     * Try to find the mocap FakePlayer entity and start an EntityAudioChannel.
     * If the entity isn't in the world yet, queue as pending to retry each tick.
     */
    private void enqueueOrStartChannel(PlayerData data, int iter, int seg, short[] pcm,
                                         int iterationStartTick) {
        String nickname = data.getNickname();
        net.minecraft.world.entity.Entity mcEntity = findMocapEntity(nickname);

        if (mcEntity != null) {
            startEntityChannel(data, iter, seg, pcm, mcEntity, iterationStartTick);
        } else {
            // Entity not spawned yet — queue and retry each tick
            TimeLoop.LOOP_LOGGER.info(
                    "[Voice] Mocap entity '{}' not found yet, queuing audio for {} i{} s{}",
                    nickname, data.getName(), iter, seg
            );
            pendingChannels.add(new PendingChannel(
                    data, iter, seg, pcm, TimeLoop.tickCounter, iterationStartTick
            ));
        }
    }

    /**
     * Starts an {@link EntityAudioChannel} bound to the mocap FakePlayer entity.
     * This makes the SVC speaker icon appear above the entity's head when it
     * is playing back voice audio — just like a real player talking.
     */
    private void startEntityChannel(PlayerData data, int iter, int seg,
                                    short[] pcm,
                                    net.minecraft.world.entity.Entity mcEntity,
                                    int iterationStartTick) {
        String key = key(data.getName(), iter, seg);

        de.maxhenkel.voicechat.api.Entity svcEntity = serverApi.fromEntity(mcEntity);
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(
                UUID.randomUUID(), svcEntity
        );

        if (channel == null) {
            TimeLoop.LOOP_LOGGER.warn(
                    "[Voice] Failed to create EntityAudioChannel for '{}', falling back to locational",
                    data.getNickname()
            );
            startLocationalChannel(data, iter, seg, pcm, iterationStartTick);
            return;
        }

        channel.setCategory(LOOP_CATEGORY.getId());
        float distance = getVoiceDistance();
        channel.setDistance(distance);

        AudioPlayer player = createTickGatedAudioPlayer(channel, pcm, iterationStartTick, mcEntity);

        entityChannels.put(key, channel);
        players.put(key, player);

        player.startPlaying();
        TimeLoop.LOOP_LOGGER.info(
                "[Voice] Started EntityAudioChannel for '{}' (key={}, distance={})",
                data.getNickname(), key, distance
        );
    }

    /**
     * Fallback: starts a positional {@link LocationalAudioChannel} when no
     * mocap entity could be found (e.g. entity never spawned within timeout).
     */
    private void startLocationalChannel(PlayerData data, int iter, int seg,
                                        short[] pcm, int iterationStartTick) {
        String key = key(data.getName(), iter, seg);

        de.maxhenkel.voicechat.api.ServerLevel level =
                serverApi.fromServerLevel(TimeLoop.serverLevel);

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                UUID.randomUUID(), level, serverApi.createPosition(0, 0, 0)
        );

        channel.setCategory(LOOP_CATEGORY.getId());
        float distance = getVoiceDistance();
        channel.setDistance(distance);

        AudioPlayer player = createTickGatedAudioPlayer(channel, pcm, iterationStartTick, null);

        locationalChannels.put(key, channel);
        players.put(key, player);

        player.startPlaying();
        TimeLoop.LOOP_LOGGER.info(
                "[Voice] Started LocationalAudioChannel (fallback) for '{}' (key={}, distance={})",
                data.getName(), key, distance
        );
    }

    /**
     * Creates a tick-gated AudioPlayer that feeds PCM frames at a rate
     * synchronized with server ticks. Used by both entity and locational channels.
     * <p>
     * The PCM buffer is indexed from tick 0 of the loop iteration, so
     * {@code iterationStartTick} must be the tick counter value at the moment
     * the current iteration began — NOT the current tick when this method is
     * called. This ensures audio stays synchronised even if channel creation
     * is delayed (e.g. waiting for the mocap entity to spawn).
     *
     * @param channel            the SVC audio channel
     * @param pcm                decoded PCM buffer for the full loop duration
     * @param iterationStartTick tick counter at iteration start
     * @param boundEntity        the mocap entity (for game event emission), or null for locational
     */
    private AudioPlayer createTickGatedAudioPlayer(AudioChannel channel, short[] pcm,
                                                   int iterationStartTick,
                                                   net.minecraft.world.entity.Entity boundEntity) {
        final int[] cursor = {0};

        return serverApi.createAudioPlayer(
                channel,
                serverApi.createEncoder(),
                () -> {
                    int elapsed = TimeLoop.tickCounter - iterationStartTick;
                    int maxSample = (elapsed + TICK_LEAD) * SAMPLES_PER_TICK;

                    if (cursor[0] + FRAME_SIZE > pcm.length) return null;

                    // Feed silence when paused or caught up to tick limit
                    if (cursor[0] >= maxSample) {
                        return new short[FRAME_SIZE];
                    }

                    short[] out = new short[FRAME_SIZE];
                    System.arraycopy(pcm, cursor[0], out, 0, FRAME_SIZE);
                    cursor[0] += FRAME_SIZE;

                    // Emit game event from the replay entity when non-silent audio
                    // is being played. This triggers sculk sensors / warden for
                    // replay entities (vcinteraction only handles live
                    // MicrophonePacketEvents, not our playback). Requires
                    // vcinteraction to be installed (voiceEvent is null otherwise).
                    if (boundEntity != null && TimeLoop.voiceInteractionEnabled && !AudioUtils.isSilent(out)) {
                        // Schedule on the server thread (this supplier runs on
                        // the AudioPlayer thread)
                        TimeLoop.server.execute(() ->
                                VoicechatInteractionCompat.emitIfLoudEnough(boundEntity, out)
                        );
                    }

                    return out;
                }
        );
    }

    /* ───────────── TICK UPDATE ───────────── */

    public void onTick() {
        // Process any pending channels waiting for their mocap entity to appear
        processPendingChannels();

        int tick = TimeLoop.tickCounter;

        // Update locational channels (fallback) — entity channels move with the entity automatically
        locationalChannels.forEach((key, channel) -> {
            ParsedKey k = ParsedKey.parse(key);
            PlayerData data =
                    TimeLoop.loopSceneManager.getRecordingPlayer(k.player);

            if (data == null) return;

            var pos = data.getPosition(k.iter, k.segment, tick);
            if (pos != null) {
                channel.updateLocation(
                        serverApi.createPosition(pos.x, pos.y, pos.z)
                );
            }
        });
    }

    /**
     * Processes pending channels: for each queued decoded PCM buffer, try to
     * find the mocap FakePlayer entity. If found, start an EntityAudioChannel.
     * If the wait exceeds {@link #MAX_ENTITY_WAIT_TICKS}, fall back to a
     * LocationalAudioChannel so audio isn't lost.
     */
    private void processPendingChannels() {
        if (pendingChannels.isEmpty()) return;

        List<PendingChannel> snapshot;
        synchronized (pendingChannels) {
            snapshot = new ArrayList<>(pendingChannels);
            pendingChannels.clear();
        }

        for (PendingChannel pending : snapshot) {
            net.minecraft.world.entity.Entity mcEntity =
                    findMocapEntity(pending.data.getNickname());

            if (mcEntity != null) {
                startEntityChannel(
                        pending.data, pending.iter, pending.seg,
                        pending.pcm, mcEntity, pending.iterationStartTick
                );
            } else if (TimeLoop.tickCounter - pending.queuedAtTick >= MAX_ENTITY_WAIT_TICKS) {
                TimeLoop.LOOP_LOGGER.warn(
                        "[Voice] Timed out waiting for mocap entity '{}', using locational fallback for {} i{} s{}",
                        pending.data.getNickname(), pending.data.getName(),
                        pending.iter, pending.seg
                );
                startLocationalChannel(
                        pending.data, pending.iter, pending.seg, pending.pcm,
                        pending.iterationStartTick
                );
            } else {
                // Still waiting — put it back
                pendingChannels.add(pending);
            }
        }
    }

    /* ───────────── ENTITY LOOKUP ───────────── */

    /**
     * Scans the server level for a mocap FakePlayer entity matching the given
     * nickname. Mocap's FakePlayer extends ServerPlayer and is added to the
     * level via {@code addNewPlayer()} — it does NOT carry the
     * {@code "mocap_entity"} tag (that tag is only for spawned items/vehicles).
     * <p>
     * We identify it by finding a ServerPlayer whose GameProfile name matches
     * the nickname but who is NOT a real connected player (i.e. not in the
     * server's player list).
     *
     * @param nickname the display name of the mocap entity
     * @return the matching entity, or {@code null} if not found
     */
    private static net.minecraft.world.entity.Entity findMocapEntity(String nickname) {
        if (TimeLoop.serverLevel == null || nickname == null || TimeLoop.server == null) return null;

        // Build a set of UUIDs of real connected players so we can exclude them
        Set<java.util.UUID> realPlayerUUIDs = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer sp : TimeLoop.server.getPlayerList().getPlayers()) {
            realPlayerUUIDs.add(sp.getUUID());
        }

        for (net.minecraft.world.entity.Entity entity : TimeLoop.serverLevel.getAllEntities()) {
            // Mocap FakePlayer extends ServerPlayer
            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // Skip real connected players
                if (realPlayerUUIDs.contains(serverPlayer.getUUID())) continue;
                // Match by GameProfile name (the nickname passed to mocap playback)
                if (nickname.equals(serverPlayer.getGameProfile().name())) {
                    return entity;
                }
            }
        }
        return null;
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
            // Stop ALL playback
            players.values().forEach(AudioPlayer::stopPlaying);
            players.clear();
            entityChannels.clear();
            locationalChannels.clear();
            pendingChannels.clear();
            return;
        }
        players.forEach((k, p) -> {
            if (k.startsWith(player + "|")) p.stopPlaying();
        });
        players.keySet().removeIf(k -> k.startsWith(player + "|"));
        entityChannels.keySet().removeIf(k -> k.startsWith(player + "|"));
        locationalChannels.keySet().removeIf(k -> k.startsWith(player + "|"));
        pendingChannels.removeIf(p -> p.data.getName().equals(player));
    }

    /* ───────────── KEY / HELPER TYPES ───────────── */

    private static String key(String p, int i, int s) {
        return p + "|" + i + "|" + s;
    }

    private record ParsedKey(String player, int iter, int segment) {
        static ParsedKey parse(String k) {
            String[] p = k.split("\\|");
            return new ParsedKey(p[0],
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]));
        }
    }

    /**
     * Holds decoded PCM audio that is waiting for its mocap entity to appear.
     */
    private record PendingChannel(PlayerData data, int iter, int seg,
                                  short[] pcm, int queuedAtTick,
                                  int iterationStartTick) {}
}
