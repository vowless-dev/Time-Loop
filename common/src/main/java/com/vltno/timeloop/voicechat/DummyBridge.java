package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;

public class DummyBridge implements Bridge {
    // Do nothing

    @Override
    public void onStartPlayback(PlayerData playerData) {}

    @Override
    public void onTick() {}

    @Override
    public void stopPlayback(String playerName) {}
}
