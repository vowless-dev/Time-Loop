package com.vltno.timeloop.compat;

import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.compat.voicechat.AudioUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integration with <a href="https://github.com/henkelmax/voicechat-interaction">voicechat-interaction</a>.
 * <p>
 * When vcinteraction is installed it handles game event emission for <b>live
 * players</b> via its own {@code MicrophonePacketEvent} listener. However, it
 * knows nothing about our <b>mocap replay entities</b>. This class fills that
 * gap: it looks up vcinteraction's {@code vcinteraction:voice} {@link GameEvent}
 * from the registry (reusing its sculk frequency mapping) and emits it from
 * replay entities during voice playback.
 * <p>
 * When vcinteraction is <b>not</b> installed, this class is a complete no-op -
 * {@code voiceEvent} remains {@code null} and all emission methods return
 * immediately. No sculk/warden interaction occurs from voice without
 * vcinteraction.
 * <p>
 * Threshold and cooldown for replay-entity emission are taken from our own
 * config ({@link TimeLoop#voiceInteractionThresholdDb},
 * {@link TimeLoop#voiceInteractionCooldownTicks}).
 */
public final class VoicechatInteractionCompat {

    private VoicechatInteractionCompat() {}

    /** The resolved game event ({@code vcinteraction:voice} or {@code timeloop:voice}), or {@code null} if disabled. */
    private static Holder<GameEvent> voiceEvent;

    /** Per-entity cooldown tracking (UUID -> last game-time the event was emitted). */
    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    // ─── game event resolution ─────────────────────────────────────────

    /**
     * Resolves the {@link GameEvent} to use for replay-entity voice emission.
     * Must be called on server start, after registries are frozen.
     * <p>
     * When voiceinteraction is installed, looks up {@code vcinteraction:voice}
     * (which carries the correct sculk sensor frequency mapping). Falls back
     * to our own {@code timeloop:voice} if that lookup fails. When
     * vcinteraction is not installed, sets {@code voiceEvent} to {@code null}
     * so all emission is disabled.
     */
    public static void resolveGameEvent() {
        if (!TimeLoop.voiceInteractionLoaded) {
            voiceEvent = null;
            TimeLoop.LOOP_LOGGER.info("[Compat] voicechat-interaction not installed - voice game events disabled");
            return;
        }

        // Try vcinteraction's own event first (has proper sculk frequency mapping)
        Identifier vcLoc = Identifier.fromNamespaceAndPath("vcinteraction", "voice");
        var vcHolder = BuiltInRegistries.GAME_EVENT.get(vcLoc);
        if (vcHolder.isPresent()) {
            voiceEvent = vcHolder.get();
            TimeLoop.LOOP_LOGGER.info("[Compat] Using vcinteraction:voice GameEvent for replay entities");
            return;
        }
        TimeLoop.LOOP_LOGGER.warn("[Compat] vcinteraction loaded but its GameEvent not found - falling back to timeloop:voice");

        // Fall back to our own
        Identifier tlLoc = Identifier.fromNamespaceAndPath("timeloop", "voice");
        var tlHolder = BuiltInRegistries.GAME_EVENT.get(tlLoc);
        if (tlHolder.isPresent()) {
            voiceEvent = tlHolder.get();
            TimeLoop.LOOP_LOGGER.info("[Compat] Using timeloop:voice GameEvent for replay entities");
        } else {
            TimeLoop.LOOP_LOGGER.error("[Compat] timeloop:voice GameEvent not found! Voice game events disabled.");
            voiceEvent = null;
        }
    }

    // ─── game event emission ───────────────────────────────────────────

    /**
     * Emits the voice game event from the given entity if the per-entity
     * cooldown has elapsed.
     * <p>
     * The caller is responsible for determining whether the audio is loud
     * enough to warrant emission (e.g. via
     * {@link AudioUtils#isAboveThreshold AudioUtils.isAboveThreshold()}).
     * This method handles only cooldown enforcement and game event dispatch.
     *
     * @param entity the entity that is "speaking" (mocap FakePlayer)
     */
    public static void emitVoiceEvent(Entity entity) {
        if (voiceEvent == null) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        long gameTime = level.getGameTime();
        Long lastEmit = cooldowns.get(entity.getUUID());
        if (lastEmit != null && gameTime - lastEmit < TimeLoop.voiceInteractionCooldownTicks) return;

        cooldowns.put(entity.getUUID(), gameTime);
        entity.gameEvent(voiceEvent);
    }

    /** Clears all per-entity cooldowns. Call on loop stop or server shutdown. */
    public static void clearCooldowns() {
        cooldowns.clear();
    }
}
