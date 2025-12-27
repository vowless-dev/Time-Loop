package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.world.phys.Vec3;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoicechatBridge implements Bridge {
    public static VoicechatServerApi serverApi;
    public static de.maxhenkel.voicechat.api.VolumeCategory LOOP_CATEGORY;

    private final Map<String, LocationalAudioChannel> activeChannels = new ConcurrentHashMap<>();
    private final Map<String, AudioPlayer> activePlayers = new ConcurrentHashMap<>();

    // BORROWED FROM REPO: A cache so we don't re-decode old loops every 30 seconds
    private final Map<String, short[]> decodedAudioCache = new ConcurrentHashMap<>();

    private final ExecutorService decoderExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setPriority(Thread.MAX_PRIORITY); // Give audio decoding the most "power"
        return t;
    });

    @Override
    public void onStartPlayback(PlayerData playerData) {
        if (serverApi == null) return;
        stopPlayback(playerData.getName());

        final int currentIteration = TimeLoop.loopIteration;
        final ServerLevel apiLevel = serverApi.fromServerLevel(TimeLoop.serverLevel);
        final String pName = playerData.getName();

        CompletableFuture.runAsync(() -> {
            for (int iter = 0; iter < currentIteration; iter++) {
                Map<Integer, List<TimedAudioFrame>> segments = playerData.getTimedVoiceDataForIteration(iter);
                if (segments == null) continue;

                for (Map.Entry<Integer, List<TimedAudioFrame>> entry : segments.entrySet()) {
                    int segmentIndex = entry.getKey();
                    String cacheKey = pName + "-" + iter + "-" + segmentIndex;

                    // BORROWED LOGIC: Check cache first to prevent lag spikes
                    short[] fullLoopBuffer = decodedAudioCache.computeIfAbsent(cacheKey, k -> {
                        short[] buffer = new short[TimeLoop.loopLengthTicks * 2400];
                        OpusDecoder decoder = serverApi.createDecoder();

                        try {
                            int lastExpectedSample = 0;

                            for (TimedAudioFrame frame : entry.getValue()) {
                                // Each frame index represents exactly 960 samples (20ms)
                                int targetSampleIndex = frame.frameIndex() * 960;

                                // PLC Smoothing for gaps
                                while (lastExpectedSample < targetSampleIndex) {
                                    short[] plcFrame = decoder.decode(null);
                                    int toCopy = Math.min(960, targetSampleIndex - lastExpectedSample);
                                    if (lastExpectedSample + toCopy <= buffer.length) {
                                        System.arraycopy(plcFrame, 0, buffer, lastExpectedSample, toCopy);
                                    }
                                    lastExpectedSample += toCopy;
                                }

                                short[] decoded = decoder.decode(frame.data());
                                if (targetSampleIndex + decoded.length <= buffer.length) {
                                    System.arraycopy(decoded, 0, buffer, targetSampleIndex, decoded.length);
                                    lastExpectedSample = targetSampleIndex + decoded.length;
                                }
                            }
                        } finally {
                            if (decoder != null && !decoder.isClosed()) decoder.close();
                        }
                        return buffer;
                    });

                    dumpAudioToFile(TimeLoop.worldFolder.resolve(cacheKey + ".raw").toString(), fullLoopBuffer); // TODO - DEBUG! REMOVE BEFORE RELEASE!!!

                    // Start the audio pointer at the EXACT master tick
                    final int startPointer = TimeLoop.tickCounter * 2400;
                    if (startPointer >= fullLoopBuffer.length) continue;

                    LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                            UUID.randomUUID(), apiLevel, serverApi.createPosition(0,0,0)
                    );
                    channel.setCategory(LOOP_CATEGORY.getId());

                    // We use a queue to "pre-load" audio frames so the player never starves
                    Queue<short[]> frameQueue = new LinkedList<>();

                    // 1. Pre-fill the queue with a few frames (e.g., 100ms / 5 frames)
                    // to give the player a head start.
                    int preloadPointer = startPointer;
                    for (int i = 0; i < 5 && (preloadPointer + 960) <= fullLoopBuffer.length; i++) {
                        short[] initialFrame = new short[960];
                        System.arraycopy(fullLoopBuffer, preloadPointer, initialFrame, 0, 960);
                        frameQueue.add(initialFrame);
                        preloadPointer += 960;
                    }

                    final int[] currentPointer = { preloadPointer };

                    AudioPlayer player = serverApi.createAudioPlayer(channel, serverApi.createEncoder(), () -> {
                        if (TimeLoop.server.isPaused()) return new short[960];

                        // If the queue has data, give it to the player immediately
                        if (!frameQueue.isEmpty()) {
                            return frameQueue.poll();
                        }

                        // If the queue is empty, try to grab the next frame from the main buffer
                        if (currentPointer[0] + 960 <= fullLoopBuffer.length) {
                            short[] frame = new short[960];
                            System.arraycopy(fullLoopBuffer, currentPointer[0], frame, 0, 960);
                            currentPointer[0] += 960;

                            // BORROWED TIP: Occasionally "over-read" to keep the queue healthy
                            // This prevents the "choppy" sound if the server lags for a millisecond
                            if (currentPointer[0] + 960 <= fullLoopBuffer.length) {
                                short[] nextFrame = new short[960];
                                System.arraycopy(fullLoopBuffer, currentPointer[0], nextFrame, 0, 960);
                                frameQueue.add(nextFrame);
                                currentPointer[0] += 960;
                            }

                            return frame;
                        }

                        return null; // End of recording
                    });

                    player.startPlaying();
                    activeChannels.put(cacheKey, channel);
                    activePlayers.put(cacheKey, player);
                }
            }
        }, decoderExecutor);
    }

    @Override
    public void onTick() {
        if (serverApi == null || activeChannels.isEmpty()) return;

        int masterTick = TimeLoop.tickCounter;

        activeChannels.forEach((ghostKey, channel) -> {
            String[] parts = ghostKey.split("-");
            String pName = parts[0];
            int iter = Integer.parseInt(parts[1]);
            int segment = Integer.parseInt(parts[2]);

            PlayerData data = TimeLoop.loopSceneManager.getRecordingPlayer(pName);
            if (data != null) {
                Vec3 pos = data.getPositionAtFrame(iter, segment, masterTick);
                if (pos != null) {
                    channel.updateLocation(serverApi.createPosition(pos.x, pos.y, pos.z));
                }
            }
        });
    }

    @Override
    public void stopPlayback(String playerName) {
        activePlayers.keySet().removeIf(key -> {
            if (key.startsWith(playerName + "-")) {
                AudioPlayer p = activePlayers.get(key);
                if (p != null) p.stopPlaying();
                return true;
            }
            return false;
        });
        activeChannels.keySet().removeIf(key -> key.startsWith(playerName + "-"));
    }

    // IMPORTANT: Clear the cache when the loop is totally reset/cleared
    public void clearCache() {
        decodedAudioCache.clear();
    }

    public void dumpAudioToFile(String fileName, short[] pcmData) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(fileName))) {
            for (short s : pcmData) {
                // Write Little Endian PCM (Standard for audio players)
                dos.writeShort(Short.reverseBytes(s));
            }
            TimeLoop.LOOP_LOGGER.info("DEBUG: Audio dumped to {}", fileName);
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("DEBUG: Failed to dump audio", e);
        }
    }
}