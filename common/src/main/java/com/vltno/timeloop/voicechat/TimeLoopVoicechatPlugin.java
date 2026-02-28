package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

public class TimeLoopVoicechatPlugin {
    public static String getPluginId() {
        return "timeloop";
    }

    public static void initialize(VoicechatApi api) {
        TimeLoop.LOOP_LOGGER.info("Initialised VC");
    }

    public static void registerEvents(EventRegistration reg) {
        reg.registerEvent(MicrophonePacketEvent.class, TimeLoopVoicechatPlugin::onMic);
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> {
            VoicechatBridge.serverApi = e.getVoicechat();
            VoicechatBridge.initCategory();
        });
    }

    private static void onMic(MicrophonePacketEvent event) {
        if (!TimeLoop.isLooping) return;
        if (!TimeLoop.trackVoice) return;
        if (event.getSenderConnection() == null) return;

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData.length == 0) return;

        var player = TimeLoop.server.getPlayerList()
                .getPlayer(event.getSenderConnection().getPlayer().getUuid());

        if (player == null) return;

        PlayerData data =
                TimeLoop.loopSceneManager.getRecordingPlayer(player.getName().getString());

        if (data == null || !data.getActive()) return;

        data.addVoiceFrame(
                TimeLoop.loopIteration,
                TimeLoop.tickCounter,
                opusData,
                player.position()
        );

        // Game event emission for live players (sculk/warden) is handled
        // entirely by voicechat-interaction when it is installed. When VCI
        // is NOT installed, voice game events are disabled — no sculk interaction.
    }

}
