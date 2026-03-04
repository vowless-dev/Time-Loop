package com.vltno.timeloop.neoforge.compat.voicechat;

import com.vltno.timeloop.compat.voicechat.TimeLoopVoicechatPlugin;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;

@ForgeVoicechatPlugin
public class TimeLoopVoicechatPluginNeoForge implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return TimeLoopVoicechatPlugin.getPluginId();
    }

    @Override
    public void initialize(VoicechatApi api) {
        TimeLoopVoicechatPlugin.initialize(api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        TimeLoopVoicechatPlugin.registerEvents(registration);
    }
}
