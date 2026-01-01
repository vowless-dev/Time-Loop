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

    private final Map<String, short[]> decodedAudioCache = new ConcurrentHashMap<>();

    // Use available processors to prevent bottlenecking during loop restarts
    private final ExecutorService decoderExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
        Thread t = new Thread(r);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    @Override
    public void onStartPlayback(PlayerData playerData) {
        if (serverApi == null) return;

        // Stop current iterations for this player before starting new ones
        stopPlayback(playerData.getName());

        final int currentIteration = TimeLoop.loopIteration;
        final ServerLevel apiLevel = serverApi.fromServerLevel(TimeLoop.serverLevel);
        final String pName = playerData.getName();
        final int masterStartTick = TimeLoop.tickCounter;

        CompletableFuture.runAsync(() -> {
            for (int iter = 0; iter < currentIteration; iter++) {
                Map<Integer, List<TimedAudioFrame>> segments = playerData.getTimedVoiceDataForIteration(iter);
                if (segments == null) continue;

                for (Map.Entry<Integer, List<TimedAudioFrame>> entry : segments.entrySet()) {
                    int segmentIndex = entry.getKey();
                    String cacheKey = pName + "-" + iter + "-" + segmentIndex;

                    short[] fullLoopBuffer = decodedAudioCache.computeIfAbsent(cacheKey, k -> {
                        // Max length based on loop ticks * 48kHz (2400 samples per tick)
                        short[] buffer = new short[TimeLoop.loopLengthTicks * 2400];
                        OpusDecoder decoder = serverApi.createDecoder();

                        try {
                            int lastExpectedSample = 0;
                            for (TimedAudioFrame frame : entry.getValue()) {
                                int targetSampleIndex = frame.frameIndex() * 960;

                                // Packet Loss Concealment (PLC) for gaps
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

                    // Start the audio pointer at the EXACT master tick (converted to 20ms frames)
                    int currentFrameIndex = (int) (masterStartTick * (50.0 / 20.0));
                    final int startPointer = currentFrameIndex * 960;

                    if (startPointer >= fullLoopBuffer.length) continue;

                    LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                            UUID.randomUUID(), apiLevel, serverApi.createPosition(0,0,0)
                    );
                    channel.setCategory(LOOP_CATEGORY.getId());

                    Queue<short[]> frameQueue = new LinkedList<>();
                    int preloadPointer = startPointer;

                    // Pre-fill 100ms of audio to prevent starvation
                    for (int i = 0; i < 5 && (preloadPointer + 960) <= fullLoopBuffer.length; i++) {
                        short[] initialFrame = new short[960];
                        System.arraycopy(fullLoopBuffer, preloadPointer, initialFrame, 0, 960);
                        frameQueue.add(initialFrame);
                        preloadPointer += 960;
                    }

                    final int[] currentPointer = { preloadPointer };

                    AudioPlayer player = serverApi.createAudioPlayer(channel, serverApi.createEncoder(), () -> {
                        if (TimeLoop.server.isPaused()) return new short[960];

                        if (!frameQueue.isEmpty()) return frameQueue.poll();

                        if (currentPointer[0] + 960 <= fullLoopBuffer.length) {
                            short[] frame = new short[960];
                            System.arraycopy(fullLoopBuffer, currentPointer[0], frame, 0, 960);
                            currentPointer[0] += 960;

                            // Queue health: occasionally buffer ahead
                            if (currentPointer[0] + 960 <= fullLoopBuffer.length) {
                                short[] nextFrame = new short[960];
                                System.arraycopy(fullLoopBuffer, currentPointer[0], nextFrame, 0, 960);
                                frameQueue.add(nextFrame);
                                currentPointer[0] += 960;
                            }
                            return frame;
                        }
                        return null;
                    });

                    dumpAudioToFile(String.format("%s-%d-%d.pcm", playerData.getName(), TimeLoop.loopIteration, playerData.getActiveRecordingIndex()), fullLoopBuffer);

                    player.startPlaying();
                    activeChannels.put(cacheKey, channel);
                    activePlayers.put(cacheKey, player);

                    // Tiny stagger to prevent network burst spikes
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
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
                // If the mocap playback started at tick 0, this is sync'd.
                Vec3 pos = data.getPositionAtFrame(iter, segment, masterTick);
                if (pos != null) {
                    channel.updateLocation(serverApi.createPosition(pos.x, pos.y, pos.z));
                }
            }
        });
    }

    @Override
    public void stopPlayback(String playerName) {
        activePlayers.keySet().forEach(key -> {
            if (key.startsWith(playerName + "-")) {
                AudioPlayer p = activePlayers.get(key);
                if (p != null) p.stopPlaying();
            }
        });
        activePlayers.keySet().removeIf(key -> key.startsWith(playerName + "-"));
        activeChannels.keySet().removeIf(key -> key.startsWith(playerName + "-"));
    }

    public void clearCache() {
        decodedAudioCache.clear();
    }

    public void dumpAudioToFile(String fileName, short[] pcmData) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(fileName))) {
            for (short s : pcmData) {
                dos.writeShort(Short.reverseBytes(s));
            }
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to dump audio", e);
        }
    }
}