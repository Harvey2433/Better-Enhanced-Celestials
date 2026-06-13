package dev.corgitaco.enhancedcelestials.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import dev.corgitaco.enhancedcelestials.EnhancedCelestials;
import dev.corgitaco.enhancedcelestials.config.ECConfigAccess;
import dev.corgitaco.enhancedcelestials.config.LunarEventConfigData;
import dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;

public class LunarForecastCommand {
    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("lunarForecast").executes(cs -> displayLunarForecast(cs.getSource())).then(Commands.literal("recompute").executes(cs -> recompute(cs.getSource())));
    }


    public static int recompute(CommandSourceStack source) {
        ServerLevel world = source.getLevel();

        Optional<EnhancedCelestialsLunarForecastWorldData> lunarForecastWorldData = EnhancedCelestials.lunarForecastWorldData(world);

        if (lunarForecastWorldData.isEmpty()) {
            source.sendFailure(Component.translatable("enhancedcelestials.commands.disabled"));
            return 0;
        }
        EnhancedCelestialsLunarForecastWorldData data = lunarForecastWorldData.orElseThrow();
        data.recomputeForecast();
        source.sendSuccess(() -> Component.translatable("enhancedcelestials.lunarforecast.recompute"), true);


        return 1;
    }


    public static int displayLunarForecast(CommandSourceStack source) {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null && "disabled".equals(config.getForecastMode())) {
            source.sendFailure(Component.translatable("enhancedcelestials.forecast.disabled"));
            return 0;
        }

        ServerLevel world = source.getLevel();

        Optional<EnhancedCelestialsLunarForecastWorldData> lunarForecastWorldData = EnhancedCelestials.lunarForecastWorldData(world);

        if (lunarForecastWorldData.isEmpty()) {
            source.sendFailure(Component.translatable("enhancedcelestials.commands.disabled"));
            return 0;
        }

        EnhancedCelestialsLunarForecastWorldData data = lunarForecastWorldData.orElseThrow();

        List<Component> lines = data.getForecastComponents();
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }
}
