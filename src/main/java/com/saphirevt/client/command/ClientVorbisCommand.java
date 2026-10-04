package com.saphirevt.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.saphirevt.client.sound.VorbisPlayer;
import com.saphirevt.client.state.ActiveSoundtrack;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ClientVorbisCommand {

    //private static final SuggestionProvider<FabricClientCommandSource> SOUND_SUGGESTIONS = (context, builder) -> 
    //    CommandSource.suggestIdentifiers(Registries.SOUND_EVENT.getIds(), builder);

    private static final SuggestionProvider<FabricClientCommandSource> SOUND_SUGGESTIONS = (context, builder) -> {
        MinecraftClient client = MinecraftClient.getInstance();
        java.util.Set<Identifier> allSounds = new java.util.HashSet<>(Registries.SOUND_EVENT.getIds());
        if (client != null && client.getSoundManager() != null) {
                allSounds.addAll(client.getSoundManager().getKeys());
        }
        return CommandSource.suggestIdentifiers(allSounds, builder);
    };

    private static final SuggestionProvider<FabricClientCommandSource> CATEGORY_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(SoundCategory.values())
                .map(SoundCategory::getName)
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ClientVorbisCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("vorbis")
                // --- PLAY ---
                .then(literal("play")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("sound", IdentifierArgumentType.identifier())
                                        .suggests(SOUND_SUGGESTIONS)
                                        .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), SoundCategory.MUSIC, 1.0f, 1.0f, true, 0.0f, 0.0f, 0.0f))
                                        .then(argument("category", StringArgumentType.word())
                                                .suggests(CATEGORY_SUGGESTIONS)
                                                .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), 1.0f, 1.0f, true, 0.0f, 0.0f, 0.0f))
                                                .then(argument("volume", FloatArgumentType.floatArg(0.0f, 5.0f))
                                                        .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), 1.0f, true, 0.0f, 0.0f, 0.0f))
                                                        .then(argument("pitch", FloatArgumentType.floatArg(0.1f, 3.0f))
                                                                .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), getF(ctx, "pitch"), true, 0.0f, 0.0f, 0.0f))
                                                                .then(argument("loop", BoolArgumentType.bool())
                                                                        .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), getF(ctx, "pitch"), getB(ctx, "loop"), 0.0f, 0.0f, 0.0f))
                                                                        .then(argument("fadeInSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                                                .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), getF(ctx, "pitch"), getB(ctx, "loop"), getF(ctx, "fadeInSec"), 0.0f, 0.0f))
                                                                                .then(argument("fadeOutSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                                                        .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), getF(ctx, "pitch"), getB(ctx, "loop"), getF(ctx, "fadeInSec"), getF(ctx, "fadeOutSec"), 0.0f))
                                                                                        .then(argument("seekSec", FloatArgumentType.floatArg(0.0f))
                                                                                                .executes(ctx -> play(ctx.getSource(), getStr(ctx, "channel"), getId(ctx, "sound"), parseCategory(getStr(ctx, "category")), getF(ctx, "volume"), getF(ctx, "pitch"), getB(ctx, "loop"), getF(ctx, "fadeInSec"), getF(ctx, "fadeOutSec"), getF(ctx, "seekSec")))
                                                                                        )
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                // --- STOP ---
                .then(literal("stop")
                        .then(argument("channel", StringArgumentType.word())
                                .executes(ctx -> {
                                    VorbisPlayer.stop(getStr(ctx, "channel"), 0.0f);
                                    return 1;
                                })
                                .then(argument("fadeOutSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                        .executes(ctx -> {
                                            VorbisPlayer.stop(getStr(ctx, "channel"), getF(ctx, "fadeOutSec"));
                                            return 1;
                                        })
                                )
                        )
                        .executes(ctx -> {
                            VorbisPlayer.stopAll();
                            return 1;
                        })
                )
                // --- PAUSE & RESUME ---
                .then(literal("pause")
                        .then(argument("channel", StringArgumentType.word())
                                .executes(ctx -> {
                                    VorbisPlayer.setPaused(getStr(ctx, "channel"), true);
                                    return 1;
                                })
                        )
                )
                .then(literal("resume")
                        .then(argument("channel", StringArgumentType.word())
                                .executes(ctx -> {
                                    VorbisPlayer.setPaused(getStr(ctx, "channel"), false);
                                    return 1;
                                })
                        )
                )
                // --- VOLUME ---
                .then(literal("volume")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("value", FloatArgumentType.floatArg(0.0f, 5.0f))
                                        .executes(ctx -> {
                                            VorbisPlayer.setVolume(getStr(ctx, "channel"), getF(ctx, "value"), 0.0f);
                                            return 1;
                                        })
                                        .then(argument("fadeSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                .executes(ctx -> {
                                                    VorbisPlayer.setVolume(getStr(ctx, "channel"), getF(ctx, "value"), getF(ctx, "fadeSec"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
                // --- PITCH ---
                .then(literal("pitch")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("pitch", FloatArgumentType.floatArg(0.1f, 3.0f))
                                        .executes(ctx -> {
                                            VorbisPlayer.setPitch(getStr(ctx, "channel"), getF(ctx, "pitch"), 0.0f);
                                            return 1;
                                        })
                                        .then(argument("fadeSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                .executes(ctx -> {
                                                    VorbisPlayer.setPitch(getStr(ctx, "channel"), getF(ctx, "pitch"), getF(ctx, "fadeSec"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
                // --- FADE ---
                .then(literal("fade")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("fadeInSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                        .then(argument("fadeOutSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                .executes(ctx -> {
                                                VorbisPlayer.setFade(getStr(ctx, "channel"), getF(ctx, "fadeInSec"), getF(ctx, "fadeOutSec"));
                                                return 1;
                                                })
                                        )
                                )
                        )
                )
                // --- LOOP ---
                .then(literal("loop")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("loop", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                        VorbisPlayer.setLoop(getStr(ctx, "channel"), getB(ctx, "loop"), false, 0.0f);
                                        return 1;
                                        })
                                        .then(argument("force", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                VorbisPlayer.setLoop(getStr(ctx, "channel"), getB(ctx, "loop"), getB(ctx, "force"), 0.0f);
                                                return 1;
                                                })
                                                .then(argument("crossFadeSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                        .executes(ctx -> {
                                                        VorbisPlayer.setLoop(getStr(ctx, "channel"), getB(ctx, "loop"), getB(ctx, "force"), getF(ctx, "crossFadeSec"));
                                                        return 1;
                                                        })
                                                )
                                        )
                                )
                        )
                )
                // --- SEEK ---
                .then(literal("seek")
                        .then(argument("channel", StringArgumentType.word())
                                .then(argument("seconds", FloatArgumentType.floatArg(0.0f))
                                        .executes(ctx -> {
                                        VorbisPlayer.seek(getStr(ctx, "channel"), getF(ctx, "seconds"), 0.0f);
                                        return 1;
                                        })
                                        .then(argument("crossFadeSec", FloatArgumentType.floatArg(0.0f, 60.0f))
                                                .executes(ctx -> {
                                                VorbisPlayer.seek(getStr(ctx, "channel"), getF(ctx, "seconds"), getF(ctx, "crossFadeSec"));
                                                return 1;
                                                })
                                        )
                                )
                        )
                )
                // --- INFO / STATUS ---
                .then(literal("info")
                        .executes(ctx -> {
                        VorbisPlayer.sendAllChannelsInfo(ctx.getSource());
                        return 1;
                        })
                        .then(argument("channel", StringArgumentType.word())
                                .executes(ctx -> {
                                VorbisPlayer.sendChannelInfo(ctx.getSource(), getStr(ctx, "channel"));
                                return 1;
                                })
                        )
                )
        );
    }

    private static int play(FabricClientCommandSource source, String channel, String path, SoundCategory category, float volume, float pitch, boolean loop, float fadeInSec, float fadeOutSec, float seekSec) {
        ActiveSoundtrack track = new ActiveSoundtrack(channel, path, category, volume, pitch, loop, fadeInSec, fadeOutSec, seekSec);
        VorbisPlayer.play(track);

        // Отправляем фидбек только если игрок/сервер не отключил отображение команд
        //if (source.getWorld() != null && source.getWorld().getGameRules().getBoolean(net.minecraft.world.GameRules.SEND_COMMAND_FEEDBACK)) {
        //        source.sendFeedback(Text.literal("Playing on channel [" + channel + "] (" + category.getName() + "): " + path));
        //}
        
        return 1;
    }

    private static SoundCategory parseCategory(String categoryName) {
        for (SoundCategory cat : SoundCategory.values()) {
            if (cat.getName().equalsIgnoreCase(categoryName)) {
                return cat;
            }
        }
        return SoundCategory.MUSIC;
    }

    private static String getStr(CommandContext<FabricClientCommandSource> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    //@SuppressWarnings("unchecked")
    private static String getId(CommandContext<FabricClientCommandSource> ctx, String name) {
        return ctx.getArgument(name, Identifier.class).toString();
    }

    private static float getF(CommandContext<FabricClientCommandSource> ctx, String name) {
        return FloatArgumentType.getFloat(ctx, name);
    }

    private static boolean getB(CommandContext<FabricClientCommandSource> ctx, String name) {
        return BoolArgumentType.getBool(ctx, name);
    }
}