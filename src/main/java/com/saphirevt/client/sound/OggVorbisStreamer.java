package com.saphirevt.client.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class OggVorbisStreamer implements Runnable {
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int BUFFER_COUNT = 3;

    private final byte[] oggData;
    private final long loopStartSample;
    private final long loopEndSample;
    private final SoundCategory soundCategory;

    private final String soundId;

    private volatile boolean loop;
    private volatile boolean running = true;
    private volatile boolean paused = false;

    // Громкость команды
    private volatile float currentVolume = 1.0f;
    private float targetVolume = 1.0f;
    private float startVolume = 1.0f;
    private long volumeFadeStartTime = -1;
    private long volumeFadeDurationMs = 0;

    // Питч
    private volatile float currentPitch = 1.0f;
    private float targetPitch = 1.0f;
    private float startPitch = 1.0f;
    private long pitchFadeStartTime = -1;
    private long pitchFadeDurationMs = 0;

    // Fade Out при остановке
    private long stopFadeOutEndTime = -1;
    private long stopFadeOutDurationMs = 0;
    private float stopFadeOutStartVolume = 1.0f;

    // Сохраненные значения для команды /vorbis fade
    private volatile float defaultFadeInSec = 0.0f;
    private volatile float defaultFadeOutSec = 0.0f;

    // Параметры кросс-фейда при Seek/Force Loop
    private volatile long pendingSeekSample = -1;
    private volatile long pendingCrossFadeMs = 0;

    // Pending Seek
    //private volatile long pendingSeekSample = -1;

    // Счетчик позиций сэмплов
    private long currentSamplePosition = 0;

    public byte[] getOggData() { return oggData; }
    public long getLoopStartSample() { return loopStartSample; }
    public long getLoopEndSample() { return loopEndSample; }
    public boolean isLooping() { return loop; }
    public SoundCategory getSoundCategory() { return soundCategory; }
    public float getTargetVolume() { return targetVolume; }
    public float getCurrentPitch() { return currentPitch; }

    public void setLoopOnly(boolean loop) {
        this.loop = loop;
    }

    public OggVorbisStreamer(String soundId, byte[] oggData, long loopStartSample, long loopEndSample, boolean loop, long fadeInMs, float seekSec, SoundCategory category) {
        this.soundId = soundId;
        this.oggData = oggData;
        this.loopStartSample = loopStartSample;
        this.loopEndSample = loopEndSample;
        this.loop = loop;
        this.soundCategory = category != null ? category : SoundCategory.MUSIC;

        if (seekSec > 0) {
            this.pendingSeekSample = (long) (seekSec * 44100);
        }

        if (fadeInMs > 0) {
            this.currentVolume = 0.0f;
            this.targetVolume = 1.0f;
            this.startVolume = 0.0f;
            this.volumeFadeStartTime = System.currentTimeMillis();
            this.volumeFadeDurationMs = fadeInMs;
        }
    }

    public String getSoundId() { return soundId; }

    public void setVolume(float volume, long fadeMs) {
        if (fadeMs <= 0) {
            this.currentVolume = volume;
            this.targetVolume = volume;
            this.volumeFadeStartTime = -1;
        } else {
            this.startVolume = this.currentVolume;
            this.targetVolume = volume;
            this.volumeFadeStartTime = System.currentTimeMillis();
            this.volumeFadeDurationMs = fadeMs;
        }
    }

    public void setPitch(float pitch, long fadeMs) {
        if (fadeMs <= 0) {
            this.currentPitch = pitch;
            this.targetPitch = pitch;
            this.pitchFadeStartTime = -1;
        } else {
            this.startPitch = this.currentPitch;
            this.targetPitch = pitch;
            this.pitchFadeStartTime = System.currentTimeMillis();
            this.pitchFadeDurationMs = fadeMs;
        }
    }

    public void setFade(float fadeInSec, float fadeOutSec) {
        this.defaultFadeInSec = fadeInSec;
        this.defaultFadeOutSec = fadeOutSec;
    }

    public void setLoop(boolean loop, boolean force, long crossFadeMs) {
        this.loop = loop;
        if (force && pendingSeekSample == -1) {
            long targetSample = loop ? (loopStartSample >= 0 ? loopStartSample : 0) : loopEndSample;
            if (targetSample >= 0) {
                seekToSample(targetSample, crossFadeMs);
            }
        }
    }

    public void seek(float seconds, long unusedMs) {
        this.pendingSeekSample = (long) (seconds * 44100);
    }

    private void seekToSample(long sample, long crossFadeMs) {
        this.pendingSeekSample = sample;
        this.pendingCrossFadeMs = crossFadeMs;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void fadeOutAndStop(long durationMs) {
        if (durationMs <= 0) {
            stop();
            return;
        }
        this.stopFadeOutDurationMs = durationMs;
        this.stopFadeOutEndTime = System.currentTimeMillis() + durationMs;
        this.stopFadeOutStartVolume = this.currentVolume;
    }

    public void stop() {
        this.running = false;
    }

    private boolean isGamePaused() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.isPaused();
    }

    @Override
    public void run() {
        ByteBuffer encodedBuffer = MemoryUtil.memAlloc(oggData.length);
        encodedBuffer.put(oggData);
        encodedBuffer.flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            long decoder = STBVorbis.stb_vorbis_open_memory(encodedBuffer, error, null);
            if (decoder == MemoryUtil.NULL) {
                MemoryUtil.memFree(encodedBuffer);
                return;
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(decoder, info);
            int channels = info.channels();
            int sampleRate = info.sample_rate();
            int format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

            int source = AL10.alGenSources();
            IntBuffer buffers = stack.mallocInt(BUFFER_COUNT);
            AL10.alGenBuffers(buffers);

            ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(BUFFER_SIZE);

            // Первичное наполнение буферов
            for (int i = 0; i < BUFFER_COUNT; i++) {
                int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuffer);
                if (samplesRead <= 0) break;
                currentSamplePosition += samplesRead;
                pcmBuffer.limit(samplesRead * channels);
                AL10.alBufferData(buffers.get(i), format, pcmBuffer, sampleRate);
                AL10.alSourceQueueBuffers(source, buffers.get(i));
                pcmBuffer.clear();
            }

            AL10.alSourcePlay(source);

            while (running) {
                // ПАУЗА: Применяется при вызове /vorbis pause ИЛИ если игра на паузе (mc.isPaused())
                boolean shouldPause = paused || isGamePaused();

                if (shouldPause) {
                    if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                        AL10.alSourcePause(source);
                    }
                    Thread.sleep(20);
                    continue;
                } else if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                    AL10.alSourcePlay(source);
                }

                long now = System.currentTimeMillis();

                // 1. Плавная смена громкости
                if (volumeFadeStartTime != -1) {
                    long elapsed = now - volumeFadeStartTime;
                    if (elapsed >= volumeFadeDurationMs) {
                        currentVolume = targetVolume;
                        volumeFadeStartTime = -1;
                    } else {
                        float progress = (float) elapsed / volumeFadeDurationMs;
                        currentVolume = startVolume + progress * (targetVolume - startVolume);
                    }
                }

                // 2. Плавный Fade-Out
                float fadeOutFactor = 1.0f;
                if (stopFadeOutEndTime != -1) {
                    long timeLeft = stopFadeOutEndTime - now;
                    if (timeLeft <= 0) {
                        running = false;
                        break;
                    } else {
                        fadeOutFactor = (float) timeLeft / stopFadeOutDurationMs;
                    }
                }

                // 3. Плавный питч
                if (pitchFadeStartTime != -1) {
                    long elapsed = now - pitchFadeStartTime;
                    if (elapsed >= pitchFadeDurationMs) {
                        currentPitch = targetPitch;
                        pitchFadeStartTime = -1;
                    } else {
                        float progress = (float) elapsed / pitchFadeDurationMs;
                        currentPitch = startPitch + progress * (targetPitch - startPitch);
                    }
                }

                // 4. Громкость с учётом настроек игры
                float mcCategoryVol = getMinecraftCategoryVolume(soundCategory);
                float mcMasterVol = getMinecraftCategoryVolume(SoundCategory.MASTER);
                float effectiveMaster = (soundCategory == SoundCategory.MASTER) ? 1.0f : mcMasterVol;
                
                float finalGain = currentVolume * fadeOutFactor * mcCategoryVol * effectiveMaster;

                AL10.alSourcef(source, AL10.AL_GAIN, finalGain);
                AL10.alSourcef(source, AL10.AL_PITCH, currentPitch);

                // 5. Обработка Seek
                if (pendingSeekSample >= 0) {
                    long seekTarget = pendingSeekSample;
                    pendingSeekSample = -1;

                    AL10.alSourceStop(source);
                    int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                    while (queued-- > 0) {
                        AL10.alSourceUnqueueBuffers(source);
                    }

                    STBVorbis.stb_vorbis_seek_frame(decoder, (int) seekTarget);
                    currentSamplePosition = seekTarget;

                    for (int i = 0; i < BUFFER_COUNT; i++) {
                        int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuffer);
                        if (samplesRead <= 0) break;
                        currentSamplePosition += samplesRead;
                        pcmBuffer.limit(samplesRead * channels);
                        AL10.alBufferData(buffers.get(i), format, pcmBuffer, sampleRate);
                        AL10.alSourceQueueBuffers(source, buffers.get(i));
                        pcmBuffer.clear();
                    }
                    AL10.alSourcePlay(source);
                }

                // 6. Заполнение буферов и зацикливание
                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                while (processed-- > 0) {
                    int buffer = AL10.alSourceUnqueueBuffers(source);

                    if (loop && loopEndSample > 0 && currentSamplePosition >= loopEndSample) {
                        long targetSeek = (loopStartSample >= 0) ? loopStartSample : 0;
                        STBVorbis.stb_vorbis_seek_frame(decoder, (int) targetSeek);
                        currentSamplePosition = targetSeek;
                    }

                    int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuffer);
                    if (samplesRead <= 0) {
                        if (loop) {
                            long targetSeek = (loopStartSample >= 0) ? loopStartSample : 0;
                            STBVorbis.stb_vorbis_seek_frame(decoder, (int) targetSeek);
                            currentSamplePosition = targetSeek;
                            samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuffer);
                        } else {
                            running = false;
                            break;
                        }
                    }

                    if (samplesRead > 0) {
                        currentSamplePosition += samplesRead;
                        pcmBuffer.limit(samplesRead * channels);
                        AL10.alBufferData(buffer, format, pcmBuffer, sampleRate);
                        AL10.alSourceQueueBuffers(source, buffer);
                        pcmBuffer.clear();
                    }
                }

                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING && running && !shouldPause) {
                    AL10.alSourcePlay(source);
                }

                Thread.sleep(10);
            }

            AL10.alSourceStop(source);
            AL10.alDeleteSources(source);
            for (int i = 0; i < BUFFER_COUNT; i++) {
                AL10.alDeleteBuffers(buffers.get(i));
            }

            STBVorbis.stb_vorbis_close(decoder);
            MemoryUtil.memFree(pcmBuffer);
            MemoryUtil.memFree(encodedBuffer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private float getMinecraftCategoryVolume(SoundCategory category) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            return mc.options.getSoundVolume(category);
        }
        return 1.0f;
    }
}