package com.vltno.timeloop.voicechat;

public record TimedAudioFrame(int tickOffset, int frameIndex, byte[] data) {}