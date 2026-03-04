package com.vltno.timeloop.compat.voicechat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles saving and loading voice data (Opus frames + positions) to/from disk.
 * <p>
 * Directory layout under {@code <world>/timeloop_audio/}:
 * <pre>
 *   timeloop_audio/
 *     &lt;playerName&gt;/
 *       iter_&lt;N&gt;_seg_&lt;M&gt;.tlva    (TimeLoop Voice Audio — binary format)
 * </pre>
 * <p>
 * File format (.tlva):
 * <pre>
 *   [4 bytes] magic: "TLVA"
 *   [4 bytes] version: 1
 *   [4 bytes] frame count
 *   For each frame:
 *     [4 bytes] tick (int)
 *     [8 bytes] posX (double)
 *     [8 bytes] posY (double)
 *     [8 bytes] posZ (double)
 *     [4 bytes] opus data length
 *     [N bytes] opus data
 * </pre>
 */
public final class AudioPersistence {

    private static final byte[] MAGIC = {'T', 'L', 'V', 'A'};
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_EXTENSION = ".tlva";

    private static volatile ExecutorService ioPool = createIOPool();

    private static ExecutorService createIOPool() {
        return Executors.newFixedThreadPool(Math.max(2,
                Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "TimeLoop-AudioIO");
                    t.setDaemon(true);
                    return t;
                });
    }

    private static ExecutorService getIOPool() {
        ExecutorService pool = ioPool;
        if (pool == null || pool.isShutdown()) {
            synchronized (AudioPersistence.class) {
                pool = ioPool;
                if (pool == null || pool.isShutdown()) {
                    pool = createIOPool();
                    ioPool = pool;
                }
            }
        }
        return pool;
    }

    private AudioPersistence() {} // utility class

    /* ───────────── PUBLIC API ───────────── */

    /**
     * Saves all voice data for a player to disk (asynchronously).
     * Overwrites existing files for the same iteration/segment.
     *
     * @param player the player data containing audio and position maps
     * @return a future that completes when all segments are saved
     */
    public static CompletableFuture<Void> savePlayerAudio(PlayerData player) {
        Path baseDir = getPlayerDir(player.getName());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Save each iteration's audio
        for (int iter = 0; iter <= TimeLoop.loopIteration; iter++) {
            Map<Integer, List<TimedAudioFrame>> iterAudio = player.getAudio(iter);
            if (iterAudio.isEmpty()) continue;

            for (Map.Entry<Integer, List<TimedAudioFrame>> entry : iterAudio.entrySet()) {
                int seg = entry.getKey();
                List<TimedAudioFrame> frames = entry.getValue();
                if (frames.isEmpty()) continue;

                // Collect position data for each frame's tick
                final int capturedIter = iter;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        Path file = baseDir.resolve(fileName(capturedIter, seg));
                        saveSegment(file, frames, player, capturedIter, seg);
                        TimeLoop.LOOP_LOGGER.debug(
                                "[AudioIO] Saved {} frames for {} iter {} seg {}",
                                frames.size(), player.getName(), capturedIter, seg
                        );
                    } catch (IOException e) {
                        TimeLoop.LOOP_LOGGER.error(
                                "[AudioIO] Failed to save audio for {} iter {} seg {}",
                                player.getName(), capturedIter, seg, e
                        );
                    }
                }, getIOPool()));
            }
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Saves all voice data for ALL recording players (including inactive/disconnected ones,
     * since they may still have audio data in memory that hasn't been persisted).
     *
     * @return a future that completes when everything is saved
     */
    public static CompletableFuture<Void> saveAllAudio() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TimeLoop.loopSceneManager.forEachRecordingPlayer(playerData ->
                futures.add(savePlayerAudio(playerData))
        );
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Loads voice data from disk into a player's in-memory audio/position maps.
     * Runs asynchronously. Existing in-memory data for loaded iteration/segments
     * will NOT be overwritten (in-memory data takes priority).
     *
     * @param player the player data to populate
     * @return a future that completes when loading is done
     */
    public static CompletableFuture<Void> loadPlayerAudio(PlayerData player) {
        Path playerDir = getPlayerDir(player.getName());

        return CompletableFuture.runAsync(() -> {
            if (!Files.isDirectory(playerDir)) {
                TimeLoop.LOOP_LOGGER.debug(
                        "[AudioIO] No audio directory for {}", player.getName()
                );
                return;
            }

            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(playerDir, "*" + FILE_EXTENSION)) {

                int loaded = 0;
                for (Path file : stream) {
                    int[] iterSeg = parseFileName(file.getFileName().toString());
                    if (iterSeg == null) {
                        TimeLoop.LOOP_LOGGER.warn(
                                "[AudioIO] Skipping unrecognized file: {}", file
                        );
                        continue;
                    }

                    int iter = iterSeg[0];
                    int seg = iterSeg[1];

                    // Don't overwrite data that's already in memory
                    Map<Integer, List<TimedAudioFrame>> existing = player.getAudio(iter);
                    if (!existing.isEmpty() && existing.containsKey(seg)) {
                        TimeLoop.LOOP_LOGGER.debug(
                                "[AudioIO] Skipping {} iter {} seg {} (already in memory)",
                                player.getName(), iter, seg
                        );
                        continue;
                    }

                    List<FrameWithPosition> framesWithPos = loadSegment(file);
                    if (framesWithPos == null || framesWithPos.isEmpty()) continue;

                    // Feed frames into the player's data structures
                    for (FrameWithPosition fwp : framesWithPos) {
                        player.addVoiceFrameForLoad(
                                iter, seg,
                                fwp.tick, fwp.opus, fwp.position
                        );
                    }
                    loaded++;
                }

                TimeLoop.LOOP_LOGGER.info(
                        "[AudioIO] Loaded {} segment files for {}",
                        loaded, player.getName()
                );

            } catch (IOException e) {
                TimeLoop.LOOP_LOGGER.error(
                        "[AudioIO] Failed to load audio for {}", player.getName(), e
                );
            }
        }, getIOPool());
    }

    /**
     * Loads all voice data for ALL recording players (including currently inactive ones,
     * so audio is ready when they reconnect).
     *
     * @return a future that completes when everything is loaded
     */
    public static CompletableFuture<Void> loadAllAudio() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TimeLoop.loopSceneManager.forEachRecordingPlayer(playerData ->
                futures.add(loadPlayerAudio(playerData))
        );
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Deletes all saved audio files for a specific player and iteration.
     */
    public static CompletableFuture<Void> deleteIterationAudio(String playerName, int iteration) {
        Path playerDir = getPlayerDir(playerName);

        return CompletableFuture.runAsync(() -> {
            if (!Files.isDirectory(playerDir)) return;

            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(playerDir,
                                 "iter_" + iteration + "_seg_*" + FILE_EXTENSION)) {

                for (Path file : stream) {
                    Files.deleteIfExists(file);
                    TimeLoop.LOOP_LOGGER.debug(
                            "[AudioIO] Deleted {}", file
                    );
                }
            } catch (IOException e) {
                TimeLoop.LOOP_LOGGER.error(
                        "[AudioIO] Failed to delete audio for {} iter {}",
                        playerName, iteration, e
                );
            }
        }, getIOPool());
    }

    /**
     * Deletes all saved voice audio for every player — the entire
     * {@code timeloop_audio/} directory tree. Also clears in-memory audio
     * for all recording players.
     *
     * @return a future that completes when deletion is done
     */
    public static CompletableFuture<Void> deleteAllAudio() {
        return CompletableFuture.runAsync(() -> {
            // Clear in-memory audio for every player
            TimeLoop.loopSceneManager.forEachRecordingPlayer(PlayerData::removeAllAudio);

            Path baseDir = getBaseDir();
            if (!Files.isDirectory(baseDir)) return;

            try (var walker = Files.walk(baseDir)) {
                // Delete files first (deepest first), then directories
                walker.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                TimeLoop.LOOP_LOGGER.warn(
                                        "[AudioIO] Failed to delete {}", path, e
                                );
                            }
                        });
                TimeLoop.LOOP_LOGGER.info("[AudioIO] Deleted all voice audio");
            } catch (IOException e) {
                TimeLoop.LOOP_LOGGER.error(
                        "[AudioIO] Failed to walk audio directory for deletion", e
                );
            }
        }, getIOPool());
    }

    /**
     * Shuts down the IO thread pool. Call on server stop.
     */
    public static void shutdown() {
        ExecutorService pool = ioPool;
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /* ───────────── BINARY I/O ───────────── */

    private static void saveSegment(Path file,
                                    List<TimedAudioFrame> frames,
                                    PlayerData player,
                                    int iter,
                                    int seg) throws IOException {
        Files.createDirectories(file.getParent());

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {

            // Header
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(frames.size());

            // Frames
            for (TimedAudioFrame frame : frames) {
                out.writeInt(frame.tick());

                // Position lookup — use the position recorded at this tick
                Vec3 pos = player.getPosition(iter, seg, frame.tick());
                if (pos != null) {
                    out.writeDouble(pos.x);
                    out.writeDouble(pos.y);
                    out.writeDouble(pos.z);
                } else {
                    out.writeDouble(0);
                    out.writeDouble(0);
                    out.writeDouble(0);
                }

                byte[] opus = frame.opus();
                out.writeInt(opus.length);
                out.write(opus);
            }
        }
    }

    private static List<FrameWithPosition> loadSegment(Path file) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {

            // Verify magic
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                TimeLoop.LOOP_LOGGER.warn(
                        "[AudioIO] Invalid magic in file: {}", file
                );
                return null;
            }

            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                TimeLoop.LOOP_LOGGER.warn(
                        "[AudioIO] Unsupported format version {} in file: {}",
                        version, file
                );
                return null;
            }

            int frameCount = in.readInt();
            List<FrameWithPosition> frames = new ArrayList<>(frameCount);

            for (int i = 0; i < frameCount; i++) {
                int tick = in.readInt();
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                int opusLen = in.readInt();
                byte[] opus = new byte[opusLen];
                in.readFully(opus);

                frames.add(new FrameWithPosition(
                        tick, opus, new Vec3(x, y, z)
                ));
            }

            return frames;

        } catch (IOException e) {
            TimeLoop.LOOP_LOGGER.error("[AudioIO] Failed to read file: {}", file, e);
            return null;
        }
    }

    /* ───────────── PATH HELPERS ───────────── */

    private static Path getBaseDir() {
        return TimeLoop.worldFolder.resolve("timeloop_audio");
    }

    private static Path getPlayerDir(String playerName) {
        return getBaseDir().resolve(playerName.toLowerCase());
    }

    private static String fileName(int iter, int seg) {
        return "iter_" + iter + "_seg_" + seg + FILE_EXTENSION;
    }

    /**
     * Parses "iter_N_seg_M.tlva" → {N, M}, or null if the name doesn't match.
     */
    private static int[] parseFileName(String name) {
        if (!name.endsWith(FILE_EXTENSION)) return null;
        // Remove extension
        String stem = name.substring(0, name.length() - FILE_EXTENSION.length());
        // Expected: "iter_N_seg_M"
        String[] parts = stem.split("_");
        if (parts.length != 4 || !"iter".equals(parts[0]) || !"seg".equals(parts[2])) {
            return null;
        }
        try {
            int iter = Integer.parseInt(parts[1]);
            int seg = Integer.parseInt(parts[3]);
            return new int[]{iter, seg};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ───────────── INTERNAL RECORD ───────────── */

    private record FrameWithPosition(int tick, byte[] opus, Vec3 position) {}
}
