package com.vltno.timeloop.compat.voicechat;

public record TimedAudioFrame(int tick, byte[] opus) {}
