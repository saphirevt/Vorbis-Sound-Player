package com.saphirevt.client.state;

import net.minecraft.sound.SoundCategory;

public record ActiveSoundtrack(
        String channel,
        String soundId,
        SoundCategory category,
        float volume,
        float pitch,
        boolean loop,
        float fadeInSec,
        float fadeOutSec,
        float seekSec
) {
    public ActiveSoundtrack(String channel, String soundId, float volume, float pitch, boolean loop, float fadeInSec, float fadeOutSec, float seekSec) {
        this(channel, soundId, SoundCategory.MUSIC, volume, pitch, loop, fadeInSec, fadeOutSec, seekSec);
    }
}