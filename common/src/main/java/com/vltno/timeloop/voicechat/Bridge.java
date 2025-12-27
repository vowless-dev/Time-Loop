package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;

public interface Bridge {
    void onStartPlayback(PlayerData playerData);
    void onTick();

    void stopPlayback(String playerName);
}
