package dev.corgitaco.enhancedcelestials.forge;

import dev.corgitaco.enhancedcelestials.EnhancedCelestials;
import dev.corgitaco.enhancedcelestials.config.ECConfigAccess;
import dev.corgitaco.enhancedcelestials.config.ForecastStyleRenderer;
import dev.corgitaco.enhancedcelestials.core.ECRegistries;
import dev.corgitaco.enhancedcelestials.forge.config.ECForgeConfig;
import dev.corgitaco.enhancedcelestials.forge.platform.ForgeRegistrationService;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DataPackRegistryEvent;

@Mod(EnhancedCelestials.MOD_ID)
public class EnhancedCelestialsForge {

    public EnhancedCelestialsForge() {
        ECRegistries.loadClasses();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ECForgeConfig.SPEC, "enhancedcelestials/main.toml");
        ECConfigAccess.setInstance(ECForgeConfig.INSTANCE);
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::commonSetup);
        bus.addListener(this::clientSetup);
        bus.<DataPackRegistryEvent.NewRegistry>addListener(event -> ForgeRegistrationService.DATAPACK_REGISTRIES.forEach(newRegistryConsumer -> newRegistryConsumer.accept(event)));
        ForgeRegistrationService.CACHED.values().forEach(deferredRegister -> deferredRegister.register(FMLJavaModLoadingContext.get().getModEventBus()));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        EnhancedCelestials.commonSetup();
        ForecastStyleRenderer.generateDefaultCustomConfig(FMLPaths.CONFIGDIR.get());
    }

    private void clientSetup(FMLClientSetupEvent event) {
    }
}