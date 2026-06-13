package dev.corgitaco.enhancedcelestials.mixin.server;


import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.corgitaco.enhancedcelestials.EnhancedCelestials;
import dev.corgitaco.enhancedcelestials.config.ECConfigAccess;
import dev.corgitaco.enhancedcelestials.config.ForecastStyleRenderer;
import dev.corgitaco.enhancedcelestials.config.LunarEventConfigData;
import dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData;
import dev.corgitaco.enhancedcelestials.platform.services.IPlatformHelper;
import dev.corgitaco.enhancedcelestials.server.commands.LunarForecastCommand;
import dev.corgitaco.enhancedcelestials.server.commands.SetLunarEventCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(Commands.class)
public abstract class MixinCommands {

    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void addEnhancedCelestialCommands(Commands.CommandSelection $$0, CommandBuildContext $$1, CallbackInfo ci) {
        LiteralArgumentBuilder<CommandSourceStack> requires = Commands.literal(EnhancedCelestials.MOD_ID).requires(commandSource -> commandSource.hasPermission(2));
        requires.then(SetLunarEventCommand.register(dispatcher));
        requires.then(LunarForecastCommand.register(dispatcher));
        requires.then(Commands.literal("reload").executes(cs -> reloadConfig(cs.getSource())));
        dispatcher.register(requires);

        LiteralArgumentBuilder<CommandSourceStack> ecAlias = Commands.literal("ec").requires(commandSource -> commandSource.hasPermission(2));
        ecAlias.then(SetLunarEventCommand.register(dispatcher));
        ecAlias.then(LunarForecastCommand.register(dispatcher));
        ecAlias.then(Commands.literal("reload").executes(cs -> reloadConfig(cs.getSource())));
        dispatcher.register(ecAlias);
        EnhancedCelestials.LOGGER.debug("Registered Enhanced Celestial Commands!");
    }

    private static int reloadConfig(CommandSourceStack source) {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null) {
            config.forceReload();
        }

        ForecastStyleRenderer.reloadCustomConfig(IPlatformHelper.PLATFORM.configDir());

        int count = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            Optional<EnhancedCelestialsLunarForecastWorldData> data = EnhancedCelestials.lunarForecastWorldData(level);
            if (data.isPresent()) {
                data.get().reload();
                count++;
            }
        }

        if (count == 0) {
            source.sendFailure(Component.literal("No dimensions with lunar events found."));
            return 0;
        }

        final int reloadedCount = count;
        source.sendSuccess(() -> Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Config reloaded. Forecast recomputed for " + reloadedCount + " dimension(s).").withStyle(ChatFormatting.GREEN)), true);

        if (config != null) {
            List<String> enabled = new ArrayList<>();
            List<String> disabled = new ArrayList<>();
            for (String eventId : config.getRegisteredEventIds()) {
                Optional<LunarEventConfigData.EventOverride> override = config.getEventOverride(eventId);
                if (override.isPresent()) {
                    if (override.get().enabled()) {
                        enabled.add(eventId + " (" + String.format("%.0f%%", override.get().chance() * 100) + ")");
                    } else {
                        disabled.add(eventId);
                    }
                }
            }
            if (!enabled.isEmpty()) {
                MutableComponent msg = Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Enabled: ").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(String.join(", ", enabled)).withStyle(ChatFormatting.WHITE));
                source.sendSuccess(() -> msg, false);
            } else {
                MutableComponent msg = Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("No events enabled. Tick processing suspended until next reload.").withStyle(ChatFormatting.YELLOW));
                source.sendSuccess(() -> msg, false);
            }
            if (!disabled.isEmpty()) {
                MutableComponent msg = Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Disabled: ").withStyle(ChatFormatting.RED))
                        .append(Component.literal(String.join(", ", disabled)).withStyle(ChatFormatting.GRAY));
                source.sendSuccess(() -> msg, false);
            }

            MutableComponent modeMsg = Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Forecast mode: ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(config.getForecastMode()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" | Style: ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(config.getForecastStyle()).withStyle(ChatFormatting.WHITE));
            source.sendSuccess(() -> modeMsg, false);

            List<String> warnings = config.validate();
            for (String warning : warnings) {
                MutableComponent warnMsg = Component.literal("[EC] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Warning: ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(warning).withStyle(ChatFormatting.YELLOW));
                source.sendSuccess(() -> warnMsg, false);
            }
        }

        return count;
    }
}