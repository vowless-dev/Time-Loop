package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import net.minecraft.server.level.ServerPlayer;

public class TimeLoopVoicechatPlugin {
    public static String getPluginId() {
        return "timeloop";
    }

    public static void initialize(VoicechatApi api) {
        TimeLoop.LOOP_LOGGER.info("Initialised VC");
    }

    public static void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, TimeLoopVoicechatPlugin::onMicrophonePacket);
        registration.registerEvent(VoicechatServerStartedEvent.class, TimeLoopVoicechatPlugin::onVoicechatServerStarted);
    }

    private static void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection(); // Null if not sent by player
        if (connection != null && TimeLoop.isLooping) {
            ServerPlayer player = TimeLoop.server.getPlayerList().getPlayer(connection.getPlayer().getUuid());
            String playerName = player.getName().getString();
            PlayerData playerData = TimeLoop.loopSceneManager.getRecordingPlayer(playerName);

            if (playerData == null) return;

            MicrophonePacket packet = event.getPacket();

            playerData.addAudioFrame(packet.getOpusEncodedData());

            playerData.addPositionFrame(player.position());
        }
    }

    private static void onVoicechatServerStarted(VoicechatServerStartedEvent event) {
        TimeLoop.LOOP_LOGGER.info("VC server started");
        VoicechatBridge.serverApi = event.getVoicechat();

        VoicechatBridge.LOOP_CATEGORY = VoicechatBridge.serverApi.volumeCategoryBuilder().setId("timeloop_voices").setName("Time Loop Voices").setDescription("The volume of looped players' voices in the Time Loop mod").build();

        VoicechatBridge.serverApi.registerVolumeCategory(VoicechatBridge.LOOP_CATEGORY);
    }
}
