package com.saphirevt.client.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class OggLoopMetadataExtractor {
    private static final Logger LOG = LoggerFactory.getLogger("soundtrackplayer/ogg");

    public record LoopPoints(long startSample, long endSample) {}

    public static Optional<LoopPoints> extractLoopPoints(Identifier soundEventId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return Optional.empty();

        Identifier actualFileId = resolveActualSoundPath(mc, soundEventId);
        if (actualFileId == null) {
            actualFileId = soundEventId;
        }

        Identifier resourcePath = new Identifier(actualFileId.getNamespace(), actualFileId.getPath());
        Optional<Resource> resource = mc.getResourceManager().getResource(resourcePath);
        if (resource.isEmpty()) {
            return Optional.empty();
        }

        try (InputStream stream = resource.get().getInputStream()) {
            byte[] bytes = stream.readNBytes(131072);
            Map<String, String> tags = parseVorbisComments(bytes);

            double rawStart = getTagAsDouble(tags, "LOOPSTART", "LOOP_START");
            double rawLength = getTagAsDouble(tags, "LOOPLENGTH", "LOOP_LENGTH");
            double rawEnd = getTagAsDouble(tags, "LOOPEND", "LOOP_END");

            if (rawStart < 0) {
                return Optional.empty();
            }

            long startSample = parseToSamples(rawStart);
            long endSample = -1;

            if (rawLength > 0) {
                long lengthSamples = parseToSamples(rawLength);
                endSample = startSample + lengthSamples;
            } else if (rawEnd > 0) {
                endSample = parseToSamples(rawEnd);
            }

            if (startSample >= 0 && endSample > startSample) {
                LOG.info("[ogg-extractor] Resolved loop for {}: startSample={}, endSample={}", 
                        soundEventId, startSample, endSample);
                return Optional.of(new LoopPoints(startSample, endSample));
            }
        } catch (Exception e) {
            LOG.error("[ogg-extractor] Error extracting metadata for {}", soundEventId, e);
        }
        return Optional.empty();
    }

    public static Identifier resolveActualSoundPath(MinecraftClient mc, Identifier soundEventId) {
        try {
            WeightedSoundSet soundSet = mc.getSoundManager().get(soundEventId);
            if (soundSet != null) {
                Sound sound = soundSet.getSound(Random.create());
                if (sound != null) {
                    return sound.getLocation();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Map<String, String> parseVorbisComments(byte[] bytes) {
        Map<String, String> tags = new HashMap<>();
        int commentHeaderIdx = -1;
        for (int i = 0; i < bytes.length - 7; i++) {
            if (bytes[i] == 0x03 &&
                bytes[i+1] == 'v' && bytes[i+2] == 'o' && bytes[i+3] == 'r' &&
                bytes[i+4] == 'b' && bytes[i+5] == 'i' && bytes[i+6] == 's') {
                commentHeaderIdx = i + 7;
                break;
            }
        }
        if (commentHeaderIdx != -1 && commentHeaderIdx + 4 < bytes.length) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(bytes, commentHeaderIdx, bytes.length - commentHeaderIdx);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                int vendorLen = buf.getInt();
                if (vendorLen >= 0 && vendorLen < buf.remaining()) {
                    byte[] vendorBytes = new byte[vendorLen];
                    buf.get(vendorBytes);
                    tags.put("VENDOR", new String(vendorBytes, StandardCharsets.UTF_8));
                    if (buf.remaining() >= 4) {
                        int userCommentCount = buf.getInt();
                        for (int i = 0; i < userCommentCount && buf.remaining() >= 4; i++) {
                            int commentLen = buf.getInt();
                            if (commentLen > 0 && commentLen <= buf.remaining()) {
                                byte[] commentBytes = new byte[commentLen];
                                buf.get(commentBytes);
                                parseAndPutTag(new String(commentBytes, StandardCharsets.UTF_8), tags);
                            } else {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (tags.size() <= 1) {
            String rawAscii = new String(bytes, StandardCharsets.ISO_8859_1);
            String[] tokens = rawAscii.split("[\\x00-\\x1F\\x7F]");
            for (String token : tokens) {
                if (token.contains("=")) {
                    parseAndPutTag(token, tags);
                }
            }
        }
        return tags;
    }

    private static void parseAndPutTag(String entry, Map<String, String> tags) {
        int eq = entry.indexOf('=');
        if (eq > 0) {
            String key = entry.substring(0, eq).trim().toUpperCase();
            String val = entry.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                tags.put(key, val);
            }
        }
    }

    private static double getTagAsDouble(Map<String, String> tags, String... keys) {
        for (String k : keys) {
            String val = tags.get(k.toUpperCase());
            if (val != null) {
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1.0;
    }

    private static long parseToSamples(double val) {
        if (val <= 0) return -1;
        // Если значение больше 500 — это готовые сэмплы, иначе — секунды
        if (val > 500.0) {
            return (long) val;
        }
        return (long) (val * 44100.0);
    }
}