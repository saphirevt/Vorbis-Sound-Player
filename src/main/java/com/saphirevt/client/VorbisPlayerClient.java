package com.saphirevt.client;

import com.saphirevt.client.command.ClientVorbisCommand;
import com.saphirevt.client.sound.VorbisPlayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

@Environment(EnvType.CLIENT)
public class VorbisPlayerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Регистрация клиентских команд
        ClientVorbisCommand.register();

        // Очищаем стримеры при выходе из мира
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VorbisPlayer.stopAll();
        });
    }
}