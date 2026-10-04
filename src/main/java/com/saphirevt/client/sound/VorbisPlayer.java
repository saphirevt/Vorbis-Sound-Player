package com.saphirevt.client.sound;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.saphirevt.client.state.ActiveSoundtrack;

@Environment(EnvType.CLIENT)
public class VorbisPlayer {
    private static final Map<String, OggVorbisStreamer> ACTIVE_STREAMERS = new ConcurrentHashMap<>();

    public static OggVorbisStreamer getStreamer(String channel) {
        return ACTIVE_STREAMERS.get(channel);
    }

    public static void play(ActiveSoundtrack track) {
        try {
            // Остановки старого трека на том же канале перед запуском нового
            stop(track.channel(), 0.0f);

            track.soundId();
            Identifier id = new Identifier(track.soundId());
            MinecraftClient client = MinecraftClient.getInstance();

            Identifier resolvedPath = OggLoopMetadataExtractor.resolveActualSoundPath(client, id);
            if (resolvedPath == null) {
                resolvedPath = new Identifier(id.getNamespace(), id.getPath());
            } else {
                resolvedPath = new Identifier(resolvedPath.getNamespace(), resolvedPath.getPath());
            }

            Optional<Resource> resource = client.getResourceManager().getResource(resolvedPath);
            if (resource.isEmpty()) {
                System.err.println("[SoundtrackPlayer] Could not find resource file: " + resolvedPath);
                return;
            }

            byte[] oggData;
            try (InputStream resourceStream = resource.get().getInputStream()) {
                oggData = resourceStream.readAllBytes();
            }

            long loopStartSample = -1;
            long loopEndSample = -1;
            Optional<OggLoopMetadataExtractor.LoopPoints> points = OggLoopMetadataExtractor.extractLoopPoints(id);
            if (points.isPresent()) {
                loopStartSample = points.get().startSample();
                loopEndSample = points.get().endSample();
            }

            long fadeInMs = (long) (track.fadeInSec() * 1000);

            OggVorbisStreamer streamer = new OggVorbisStreamer(
                track.soundId(),
                oggData,
                loopStartSample,
                loopEndSample,
                track.loop(),
                fadeInMs,
                track.seekSec(),
                track.category()
            );

            streamer.setVolume(track.volume(), fadeInMs);
            streamer.setPitch(track.pitch(), 0);

            ACTIVE_STREAMERS.put(track.channel(), streamer);
            Thread streamerThread = new Thread(streamer, "VorbisStreamer-" + track.channel());
            streamerThread.setDaemon(true);
            streamerThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop(String channel, float fadeOutSec) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.remove(channel);
        if (streamer != null) {
            long fadeOutMs = (long) (fadeOutSec * 1000);
            if (fadeOutMs > 0) {
                streamer.fadeOutAndStop(fadeOutMs);
            } else {
                streamer.stop();
            }
        }
    }

    public static void stopAll() {
        ACTIVE_STREAMERS.forEach((channel, streamer) -> streamer.stop());
        ACTIVE_STREAMERS.clear();
    }

    public static void setPaused(String channel, boolean paused) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer != null) {
            streamer.setPaused(paused);
        }
    }

    public static void setVolume(String channel, float volume, float fadeSec) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer != null) {
            streamer.setVolume(volume, (long) (fadeSec * 1000));
        }
    }

    public static void setPitch(String channel, float pitch, float fadeSec) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer != null) {
            streamer.setPitch(pitch, (long) (fadeSec * 1000));
        }
    }

    public static void setFade(String channel, float fadeInSec, float fadeOutSec) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer != null) {
            streamer.setFade(fadeInSec, fadeOutSec);
        }
    }

    public static void setLoop(String channel, boolean loop, boolean force, float crossFadeSec) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer == null) return;

        streamer.setLoopOnly(loop); // Простая смена флага зацикливания

        if (force) {
            long targetSample = loop ? (streamer.getLoopStartSample() >= 0 ? streamer.getLoopStartSample() : 0) : streamer.getLoopEndSample();
            if (targetSample >= 0) {
                float targetSec = (float) targetSample / 44100.0f;
                seek(channel, targetSec, crossFadeSec);
            }
        }
    }

    public static void seek(String channel, float seconds, float crossFadeSec) {
        OggVorbisStreamer oldStreamer = ACTIVE_STREAMERS.get(channel);
        if (oldStreamer == null) return;

        if (crossFadeSec <= 0.0f) {
            // Если кросс-фейд 0 — обычный мгновенный прыжок внутри того же стримера
            oldStreamer.seek(seconds, 0);
            return;
        }

        // --- НАСТОЯЩИЙ ПАРАЛЛЕЛЬНЫЙ КРОСС-ФЕЙД ---
        // 1. Отправляем старый стример в плавный fade-out
        long fadeMs = (long) (crossFadeSec * 1000);
        oldStreamer.fadeOutAndStop(fadeMs);

        // 2. Создаем НОВЫЙ стример с той же музыки, но с нужной секунды и с fade-in
        OggVorbisStreamer newStreamer = new OggVorbisStreamer(
            oldStreamer.getSoundId(),
            oldStreamer.getOggData(),
            oldStreamer.getLoopStartSample(),
            oldStreamer.getLoopEndSample(),
            oldStreamer.isLooping(),
            fadeMs,           // fadeInMs равный времени кросс-фейда
            seconds,          // seekSec — стартуем сразу с нужной секунды
            oldStreamer.getSoundCategory()
        );

        newStreamer.setVolume(oldStreamer.getTargetVolume(), fadeMs);
        newStreamer.setPitch(oldStreamer.getCurrentPitch(), 0);

        // 3. Заменяем активный стример в карте каналов и запускаем поток
        ACTIVE_STREAMERS.put(channel, newStreamer);
        Thread streamerThread = new Thread(newStreamer, "VorbisStreamer-" + channel + "-xfade");
        streamerThread.setDaemon(true);
        streamerThread.start();
    }

    public static Map<String, OggVorbisStreamer> getActiveStreamers() {
        return ACTIVE_STREAMERS;
    }

    public static void sendChannelInfo(FabricClientCommandSource source, String channel) {
        OggVorbisStreamer streamer = ACTIVE_STREAMERS.get(channel);
        if (streamer == null) {
            source.sendFeedback(Text.literal("§c[Vorbis] Channel [" + channel + "] is not active or empty."));
            return;
        }

        source.sendFeedback(Text.literal("§e=== Channel Status [" + channel + "] ==="));
        source.sendFeedback(Text.literal("§fSound: §a" + streamer.getSoundId()));
        source.sendFeedback(Text.literal("§fCategory: §b" + streamer.getSoundCategory().getName()));
        source.sendFeedback(Text.literal("§fVolume: §a" + String.format("%.2f", streamer.getTargetVolume())));
        source.sendFeedback(Text.literal("§fPitch: §a" + String.format("%.2f", streamer.getCurrentPitch())));
        source.sendFeedback(Text.literal("§fLoop: " + (streamer.isLooping() ? "§aYes" : "§cNo")));
        source.sendFeedback(Text.literal("§fPaused: " + (streamer.isPaused() ? "§cYes" : "§aNo")));
    }

    public static void sendAllChannelsInfo(FabricClientCommandSource source) {
        if (ACTIVE_STREAMERS.isEmpty()) {
            source.sendFeedback(Text.literal("§c[Vorbis] No active channels."));
            return;
        }

        source.sendFeedback(Text.literal("§e=== Active Channels (" + ACTIVE_STREAMERS.size() + ") ==="));
        ACTIVE_STREAMERS.forEach((channel, streamer) -> {
            source.sendFeedback(Text.literal("§f• [§e" + channel + "§f]: §a" + streamer.getSoundId() + 
                    " §7(Vol: " + String.format("%.2f", streamer.getTargetVolume()) + 
                    ", Pitch: " + String.format("%.2f", streamer.getCurrentPitch()) + 
                    ", Loop: " + streamer.isLooping() + ")"));
        });
    }
}