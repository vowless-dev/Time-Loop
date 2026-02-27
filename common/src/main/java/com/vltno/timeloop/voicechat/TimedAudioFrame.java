package com.vltno.timeloop.voicechat;

public record TimedAudioFrame(int tick, byte[] opus) {}
