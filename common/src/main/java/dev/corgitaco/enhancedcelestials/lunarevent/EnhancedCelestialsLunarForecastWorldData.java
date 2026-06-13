package dev.corgitaco.enhancedcelestials.lunarevent;

import dev.corgitaco.dataanchor.data.TickableTrackedData;
import dev.corgitaco.dataanchor.data.registry.TrackedDataKey;
import dev.corgitaco.dataanchor.data.type.level.SyncedLevelTrackedData;
import dev.corgitaco.enhancedcelestials.EnhancedCelestials;
import dev.corgitaco.enhancedcelestials.api.EnhancedCelestialsRegistry;
import dev.corgitaco.enhancedcelestials.api.lunarevent.LunarDimensionSettings;
import dev.corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import dev.corgitaco.enhancedcelestials.api.lunarevent.LunarEventDimensionChance;
import dev.corgitaco.enhancedcelestials.api.lunarevent.LunarTextComponents;
import dev.corgitaco.enhancedcelestials.config.ECConfigAccess;
import dev.corgitaco.enhancedcelestials.config.ForecastStyleRenderer;
import dev.corgitaco.enhancedcelestials.config.LunarEventConfigData;
import dev.corgitaco.enhancedcelestials.util.CustomTranslationTextComponent;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

public class EnhancedCelestialsLunarForecastWorldData extends SyncedLevelTrackedData implements TickableTrackedData {

    protected final List<LunarEventInstance> forecast = new ArrayList<>();
    protected final List<LunarEventInstance> pastEvents = new ArrayList<>();
    private long lastCheckedDay = -1L;
    private boolean idle = false;

    private boolean shouldSync = false;

    protected transient final Holder<LunarDimensionSettings> dimensionSettingsHolder;
    protected transient Map<Holder<LunarEvent>, LunarEvent.SpawnRequirements> lunarEventSpawnRequirements;
    private transient Holder<LunarEvent> lastTickEvent;
    private transient Holder<LunarEvent> lastStoredEvent;
    private transient long lastTickDay = -1L;
    protected transient float blend = 1F;
    private transient boolean forecastBroadcastedToday = false;

    public EnhancedCelestialsLunarForecastWorldData(TrackedDataKey<EnhancedCelestialsLunarForecastWorldData> key, Level level, Holder<LunarDimensionSettings> lunarDimensionSettingsHolder, Map<Holder<LunarEvent>, LunarEvent.SpawnRequirements> lunarEventSpawnRequirementsMap) {
        super(key, level);
        this.dimensionSettingsHolder = lunarDimensionSettingsHolder;
        this.lunarEventSpawnRequirements = lunarEventSpawnRequirementsMap;
        this.lastTickEvent = currentLunarEventHolder();
        this.lastStoredEvent = currentLunarEventHolder();
        this.lastTickDay = getCurrentDay();
        String lunarEventNames = Arrays.toString(lunarEventSpawnRequirements.keySet().stream().map(Holder::unwrapKey).map(Optional::orElseThrow).map(ResourceKey::location).map(ResourceLocation::toString).toArray());
        String dimension = level.dimension().location().toString();
        EnhancedCelestials.LOGGER.info("Possible lunar events for dimension \"%s\" are %s.".formatted(dimension, lunarEventNames));

        if (!level.isClientSide) {
            LunarEventConfigData config = ECConfigAccess.get();
            if (config != null) {
                for (String warning : config.validate()) {
                    EnhancedCelestials.LOGGER.warn("[EC config] {}", warning);
                }
            }
        }
    }

    @Override
    public @Nullable CompoundTag save() {
        CompoundTag compoundTag = new CompoundTag();

        compoundTag.put("forecast", LunarEventInstance.LIST_CODEC.encodeStart(NbtOps.INSTANCE, this.forecast).getOrThrow(false, s -> {
        }));
        compoundTag.put("pastEvents", LunarEventInstance.LIST_CODEC.encodeStart(NbtOps.INSTANCE, this.pastEvents).getOrThrow(false, s -> {
        }));
        compoundTag.putLong("lastCheckedDay", this.lastCheckedDay);
        compoundTag.putBoolean("idle", this.idle);

        return compoundTag;
    }

    @Override
    public void load(CompoundTag tag) {
        this.forecast.clear();
        this.forecast.addAll(LunarEventInstance.LIST_CODEC.decode(NbtOps.INSTANCE, tag.get("forecast")).getOrThrow(false, s -> {
        }).getFirst());

        this.pastEvents.clear();
        this.pastEvents.addAll(LunarEventInstance.LIST_CODEC.decode(NbtOps.INSTANCE, tag.get("pastEvents")).getOrThrow(false, s -> {
        }).getFirst());
        this.lastCheckedDay = tag.getLong("lastCheckedDay");
        this.idle = tag.getBoolean("idle");
    }

    @Override
    public void readFromNetwork(CompoundTag tag) {
        super.readFromNetwork(tag);

        if (lastTickEvent != currentLunarEventHolder()) {
            eventSwitched(lastLunarEventHolder(), currentLunarEventHolder());
        }
    }

    @Override
    public void tick() {
        if (!level.isClientSide) {
            serverTick();
        } else {
            baseTick();
        }
    }

    private void serverTick() {
        if (this.lunarEventSpawnRequirements.isEmpty()) {
            return;
        }

        if (this.idle || allEventsDisabledByConfig()) {
            return;
        }

        long currentDay = getCurrentDay();
        boolean dayChanged = lastTickDay >= 0 && currentDay != lastTickDay;

        if (lastTickDay >= 0 && currentDay < lastTickDay - 1) {
            EnhancedCelestials.LOGGER.info("Detected backwards time jump (day {} -> {}), recomputing forecast.", lastTickDay, currentDay);
            recomputeForecast();
        } else if (dayChanged) {
            forecastBroadcastedToday = false;
            handleForecastBroadcast(dayChanged && Math.abs(currentDay - lastTickDay) > 1);
        }
        lastTickDay = currentDay;

        removeFromForecastIf(lunarEventInstance -> {
            if (lunarEventInstance.passed(getCurrentDay())) {
                this.pastEvents.add(0, lunarEventInstance);
                return true;
            }
            return lunarEventInstance.scheduledDay() > getCurrentDay() + getEffectiveYearLengthInDays();
        });

        removeFromPastEventsIf(lunarEventInstance -> lunarEventInstance.scheduledDay() > getCurrentDay() || lunarEventInstance.scheduledDay() < getCurrentDay() - getEffectiveYearLengthInDays());

        createOrUpdateForecast(lastCheckedDay);
        baseTick();

        if (shouldSync) {
            super.sync();
            shouldSync = false;
        }
    }

    private void handleForecastBroadcast(boolean wasSleepOrJump) {
        if (forecastBroadcastedToday) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        String mode = getForecastMode();
        boolean shouldBroadcast = switch (mode) {
            case "fast" -> wasSleepOrJump;
            case "auto" -> true;
            default -> false;
        };

        if (!shouldBroadcast) return;

        forecastBroadcastedToday = true;
        List<Component> forecastLines = getForecastComponents();
        for (Player player : serverLevel.players()) {
            for (Component line : forecastLines) {
                player.sendSystemMessage(line);
            }
        }
    }

    private String getForecastMode() {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null) {
            return config.getForecastMode();
        }
        return "manual";
    }

    private boolean allEventsDisabledByConfig() {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config == null) return false;
        for (Holder<LunarEvent> holder : this.lunarEventSpawnRequirements.keySet()) {
            Optional<ResourceKey<LunarEvent>> key = holder.unwrapKey();
            if (key.isEmpty()) continue;
            String eventId = key.get().location().getPath();
            Optional<LunarEventConfigData.EventOverride> override = config.getEventOverride(eventId);
            if (override.isEmpty() || override.get().enabled()) {
                return false;
            }
        }
        return true;
    }

    private void baseTick() {
        if (blend < 1F) {
            blend += 0.01F;
        }

        Holder<LunarEvent> currentEvent = currentLunarEventHolder();
        if (currentEvent != lastTickEvent) {
            eventSwitched(lastTickEvent, currentEvent);
        }
        lastTickEvent = currentEvent;
    }

    public void recomputeForecast() {
        checkServer();
        clearForecast();
        this.pastEvents.clear();
        createOrUpdateForecast(getCurrentDay());
    }

    public void reload() {
        checkServer();
        rebuildSpawnRequirements();
        if (this.lunarEventSpawnRequirements.isEmpty()) {
            cancelActiveEventIfPresent();
            clearForecast();
            this.pastEvents.clear();
            this.idle = true;
            markChanged();
            EnhancedCelestials.LOGGER.info("All lunar events disabled. Entering idle state.");
        } else {
            boolean recoveringFromIdle = this.idle;
            this.idle = false;
            if (recoveringFromIdle) {
                setLastCheckedDay(getCurrentDay());
            }
            LunarEventInstance preservedActive = getActiveEventIfAllowed();
            clearFutureForecasts();
            if (preservedActive == null) {
                cancelActiveEventIfPresent();
            }
            createOrUpdateForecast(getCurrentDay());
            markChanged();
            EnhancedCelestials.LOGGER.info("Lunar event config reloaded and forecast recomputed.");
        }
    }

    private @Nullable LunarEventInstance getActiveEventIfAllowed() {
        if (this.forecast.isEmpty()) return null;
        LunarEventInstance first = this.forecast.get(0);
        if (!first.active(getCurrentDay())) return null;
        if (this.lunarEventSpawnRequirements.containsKey(lunarEventHolder(first.getLunarEventKey()))) {
            return first;
        }
        return null;
    }

    private void cancelActiveEventIfPresent() {
        if (!this.forecast.isEmpty()) {
            LunarEventInstance first = this.forecast.get(0);
            if (first.active(getCurrentDay())) {
                this.forecast.remove(0);
            }
        }
    }

    private void clearFutureForecasts() {
        if (this.forecast.isEmpty()) return;
        LunarEventInstance first = this.forecast.get(0);
        if (first.active(getCurrentDay())) {
            this.forecast.subList(1, this.forecast.size()).clear();
        } else {
            this.forecast.clear();
        }
    }

    private void rebuildSpawnRequirements() {
        ResourceKey<Level> dimension = level.dimension();
        Registry<LunarEvent> lunarEvents = level.registryAccess().registry(EnhancedCelestialsRegistry.LUNAR_EVENT_KEY).orElseThrow();
        Registry<LunarEventDimensionChance> chancesRegistry = level.registryAccess().registryOrThrow(EnhancedCelestialsRegistry.LUNAR_EVENT_DIMENSION_CHANCE_KEY);
        Object2ObjectOpenHashMap<Holder<LunarEvent>, LunarEvent.SpawnRequirements> rebuilt = createLunarEventSpawnRequirements(dimension, chancesRegistry, lunarEvents, (Holder.Reference<LunarDimensionSettings>) this.dimensionSettingsHolder);
        applyConfigOverrides(rebuilt, lunarEvents);
        this.lunarEventSpawnRequirements = rebuilt;
    }

    private static void applyConfigOverrides(Map<Holder<LunarEvent>, LunarEvent.SpawnRequirements> requirements, Registry<LunarEvent> lunarEvents) {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config == null) return;

        Iterator<Map.Entry<Holder<LunarEvent>, LunarEvent.SpawnRequirements>> it = requirements.entrySet().iterator();
        Map<Holder<LunarEvent>, LunarEvent.SpawnRequirements> updates = new HashMap<>();

        while (it.hasNext()) {
            Map.Entry<Holder<LunarEvent>, LunarEvent.SpawnRequirements> entry = it.next();
            Holder<LunarEvent> holder = entry.getKey();
            Optional<ResourceKey<LunarEvent>> key = holder.unwrapKey();
            if (key.isEmpty()) continue;

            String eventId = key.get().location().getPath();
            Optional<LunarEventConfigData.EventOverride> override = config.getEventOverride(eventId);
            if (override.isEmpty()) continue;

            LunarEventConfigData.EventOverride ov = override.get();
            if (!ov.enabled()) {
                it.remove();
                continue;
            }

            LunarEvent.SpawnRequirements original = entry.getValue();
            java.util.Collection<Integer> moonPhases = ov.validMoonPhases().isEmpty()
                    ? original.validMoonPhases()
                    : ov.validMoonPhases();
            LunarEvent.SpawnRequirements patched = new LunarEvent.SpawnRequirements(
                    ov.chance(),
                    ov.minNightsBetween(),
                    moonPhases
            );
            updates.put(holder, patched);
        }
        requirements.putAll(updates);
    }

    private void clearForecast() {
        this.forecast.clear();
        markChanged();
    }

    public boolean switchingEvents() {
        return blend < 1F;
    }

    public void eventSwitched(Holder<LunarEvent> lastEvent, Holder<LunarEvent> nextEvent) {
        blend = 0;
        lastStoredEvent = lastEvent;
        if (!level.isClientSide) {
            serverEventSwitched(lastEvent, nextEvent);
        }
    }

    public void setLunarEvent(ResourceKey<LunarEvent> lunarEvent) {
        checkServer();

        if (this.idle) return;

        if (!lunarEvent.equals(this.dimensionSettingsHolder.value().defaultEvent())) {
            Registry<LunarEvent> registry = level.registryAccess().registry(EnhancedCelestialsRegistry.LUNAR_EVENT_KEY).orElse(null);
            if (registry != null && registry.containsKey(lunarEvent)) {
                Holder<LunarEvent> holder = registry.getHolderOrThrow(lunarEvent);
                if (!this.lunarEventSpawnRequirements.containsKey(holder)) {
                    return;
                }
            }
        }

        if (!this.level.isNight()) {
            ((ServerLevel) this.level).setDayTime((getCurrentDay() * getEffectiveDayLength()) + 13000L);
        }

        if (!this.forecast.isEmpty()) {
            LunarEventInstance first = this.forecast.get(0);
            if (first.active(getCurrentDay())) {
                removeEventInForecast(0);
            }
        }
        if (!lunarEvent.equals(this.dimensionSettingsHolder.value().defaultEvent())) {
            addEventToForecast(0, new LunarEventInstance(lunarEvent, getCurrentDay(), true));
        }
    }


    private void serverEventSwitched(Holder<LunarEvent> lastEvent, Holder<LunarEvent> nextEvent) {
        checkServer();
        LunarEventConfigData config = ECConfigAccess.get();
        ServerLevel serverLevel = (ServerLevel) level;

        for (Player player : level.players()) {
            sendEventNotification(player, lastEvent, false, config);
            sendEventNotification(player, nextEvent, true, config);
        }
    }

    private void sendEventNotification(Player player, Holder<LunarEvent> event, boolean isRising, LunarEventConfigData config) {
        ResourceKey<LunarEvent> key = event.unwrapKey().orElse(null);
        if (key == null || key.equals(this.dimensionSettingsHolder.value().defaultEvent())) return;

        String eventId = key.location().getPath();
        Optional<LunarTextComponents.Notification> notifOpt = isRising
                ? event.value().getTextComponents().riseNotification()
                : event.value().getTextComponents().setNotification();

        if (notifOpt.isEmpty()) return;
        LunarTextComponents.Notification notification = notifOpt.get();
        Component message = notification.customTranslationTextComponent().getComponent();

        String position = null;
        if (config != null) {
            Optional<LunarEventConfigData.EventOverride> override = config.getEventOverride(eventId);
            if (override.isPresent()) {
                position = override.get().notificationPosition();
            }
        }

        if (position == null) {
            if (notification.notificationType() == LunarTextComponents.NotificationType.NONE) return;
            player.displayClientMessage(message, notification.notificationType() == LunarTextComponents.NotificationType.HOT_BAR);
        } else {
            switch (position) {
                case "chat" -> player.sendSystemMessage(message);
                case "actionbar" -> player.displayClientMessage(message, true);
                case "title" -> sendTitle((net.minecraft.server.level.ServerPlayer) player, message);
                default -> {} // "disabled"
            }
        }

        if (isRising && config != null) {
            Optional<LunarEventConfigData.EventOverride> override = config.getEventOverride(eventId);
            if (override.isPresent() && override.get().soundEnabled()) {
                LunarEventConfigData.EventOverride ov = override.get();
                net.minecraft.resources.ResourceLocation soundLoc = net.minecraft.resources.ResourceLocation.tryParse(ov.soundId());
                if (soundLoc != null) {
                    net.minecraft.sounds.SoundEvent sound = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(soundLoc);
                    ((net.minecraft.server.level.ServerPlayer) player).playNotifySound(sound, net.minecraft.sounds.SoundSource.AMBIENT, (float) ov.soundVolume(), (float) ov.soundPitch());
                }
            }
        }
    }

    private void sendTitle(net.minecraft.server.level.ServerPlayer player, Component message) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(message));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 20));
    }


    public LunarEvent lastLunarEvent() {
        return lastLunarEventHolder().value();
    }

    public Holder<LunarEvent> lastLunarEventHolder() {
        return this.lastStoredEvent;
    }

    public LunarEvent currentLunarEvent() {
        return currentLunarEventHolder().value();
    }

    public Holder<LunarEvent> currentLunarEventHolder() {
        if (level.isDay()) {
            return defaultLunarEvent();
        }

        if (getEffectiveRequiresClearSkies()) {
            if (level.isRaining()) {
                return defaultLunarEvent();
            }
        }

        Holder.Reference<LunarEvent> defaultEvent = defaultLunarEvent();
        if (this.forecast.isEmpty()) {
            return defaultEvent;
        }

        LunarEventInstance first = this.forecast.get(0);
        if (first.active(getCurrentDay())) {
            return lunarEventHolder(first.getLunarEventKey());
        }

        return defaultEvent;
    }

    public Holder<LunarEvent> nextScheduledLunarEvent() {
        if (this.forecast.isEmpty()) {
            return defaultLunarEvent();
        }

        LunarEventInstance first = this.forecast.get(0);
        if (first.active(getCurrentDay())) {
            if (this.forecast.size() > 1) {
                return lunarEventHolder(this.forecast.get(1).getLunarEventKey());
            }
            return defaultLunarEvent();
        } else {
            return lunarEventHolder(first.getLunarEventKey());
        }
    }

    public Holder<LunarEvent> lastScheduledLunarEvent() {
        Holder.Reference<LunarEvent> defaultEvent = defaultLunarEvent();
        if (this.pastEvents.isEmpty()) {
            return defaultEvent;
        }

        LunarEventInstance first = this.pastEvents.get(0);
        if (first.active(getCurrentDay())) {
            return lunarEventHolder(first.getLunarEventKey());
        }

        return defaultEvent;
    }

    public Holder<LunarEvent> getLunarEventForDay(long day) {
        for (LunarEventInstance lunarEventInstance : this.forecast) {
            if (lunarEventInstance.active(day)) {
                return lunarEventHolder(lunarEventInstance.getLunarEventKey());
            }
        }
        for (LunarEventInstance lunarEventInstance : this.pastEvents) {
            if (lunarEventInstance.active(day)) {
                return lunarEventHolder(lunarEventInstance.getLunarEventKey());
            }
        }

        return defaultLunarEvent();
    }

    /**
     * @return A forecast text component to display in the chat showing up to the next 100 events.
     */
    public Component getForecastComponent() {
        return getForecastComponents().stream()
                .reduce(Component.empty(), (a, b) -> ((MutableComponent) a).append(Component.literal("\n")).append(b));
    }

    public List<Component> getForecastComponents() {
        LunarEventConfigData config = ECConfigAccess.get();
        String style = (config != null) ? config.getForecastStyle() : "normal";

        List<ForecastStyleRenderer.ForecastEntry> entries = new ArrayList<>();
        for (int i = 0; i < Math.min(100, this.forecast.size()); i++) {
            LunarEventInstance instance = this.forecast.get(i);
            Holder<LunarEvent> event = lunarEventHolder(instance.getLunarEventKey());
            CustomTranslationTextComponent name = event.value().getTextComponents().name();
            TextColor textColor = name.getStyle().getColor();
            String colorCode = textColorToSectionCode(textColor);
            String displayName = Component.translatable(name.getKey()).getString();
            long daysUntil = instance.getDaysUntil(getCurrentDay());
            String eventId = instance.getLunarEventKey().location().getPath();
            entries.add(new ForecastStyleRenderer.ForecastEntry(eventId, displayName, daysUntil, colorCode));
        }

        if (entries.isEmpty()) {
            return List.of(Component.translatable("enhancedcelestials.lunarforecast.empty"));
        }

        Path configDir = getConfigDir();
        return ForecastStyleRenderer.render(style, entries, configDir);
    }

    private static String textColorToSectionCode(TextColor color) {
        if (color == null) return "§f";
        for (ChatFormatting fmt : ChatFormatting.values()) {
            if (fmt.getColor() != null && fmt.getColor() == color.getValue()) {
                return "§" + fmt.getChar();
            }
        }
        return "§f";
    }

    private Path getConfigDir() {
        return dev.corgitaco.enhancedcelestials.platform.services.IPlatformHelper.PLATFORM.configDir();
    }

    private Holder.Reference<LunarEvent> defaultLunarEvent() {
        return lunarEventHolder(this.dimensionSettingsHolder.value().defaultEvent());
    }

    private Holder.Reference<LunarEvent> lunarEventHolder(ResourceKey<LunarEvent> lunarEventKey) {
        return level.registryAccess().registry(EnhancedCelestialsRegistry.LUNAR_EVENT_KEY).orElseThrow().getHolderOrThrow(lunarEventKey);
    }


    public Object2LongArrayMap<ResourceKey<LunarEvent>> eventsByDay() {
        Object2LongArrayMap<ResourceKey<LunarEvent>> eventByLastTime = new Object2LongArrayMap<>();

        for (LunarEventInstance lunarEventInstance : this.pastEvents) {
            eventByLastTime.put(lunarEventInstance.getLunarEventKey(), lunarEventInstance.scheduledDay());
        }

        for (LunarEventInstance lunarEventInstance : this.forecast) {
            eventByLastTime.put(lunarEventInstance.getLunarEventKey(), lunarEventInstance.scheduledDay());
        }

        return eventByLastTime;
    }

    public long lastScheduledEventDay() {
        long lastScheduledEventDay = -1L;

        for (LunarEventInstance lunarEventInstance : this.forecast) {
            lastScheduledEventDay = Math.max(lunarEventInstance.scheduledDay(), lastScheduledEventDay);
        }

        for (LunarEventInstance lunarEventInstance : this.pastEvents) {
            lastScheduledEventDay = Math.max(lunarEventInstance.scheduledDay(), lastScheduledEventDay);
        }

        return lastScheduledEventDay;
    }


    public long getCurrentDay() {
        return getDayFromDayTime(this.level.getDayTime());
    }

    public long getDayFromDayTime(long dayTime) {
        return dayTime / getEffectiveDayLength();
    }

    public long getDayTimeFromDay(long day) {
        return day * getEffectiveDayLength();
    }

    public float getBlend() {
        return blend;
    }

    public long getEffectiveMinDaysBetweenEvents() {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null && config.getMinDaysBetweenAllEvents() >= 0) {
            return config.getMinDaysBetweenAllEvents();
        }
        return this.dimensionSettingsHolder.value().minDaysBetweenEvents();
    }

    public long getEffectiveMaxDaysBetweenEvents() {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null && config.getMaxDaysBetweenAllEvents() >= 0) {
            return config.getMaxDaysBetweenAllEvents();
        }
        return this.dimensionSettingsHolder.value().maxDaysBetweenEvents();
    }

    public boolean getEffectiveRequiresClearSkies() {
        LunarEventConfigData config = ECConfigAccess.get();
        if (config != null) {
            return config.getRequiresClearSkies();
        }
        return this.dimensionSettingsHolder.value().requiresClearSkies();
    }

    public long getEffectiveDayLength() {
        LunarEventConfigData config = ECConfigAccess.get();
        long datapack = this.dimensionSettingsHolder.value().dayLength();
        if (config != null && config.getDayLength() > 0) {
            return config.getDayLength();
        }
        return datapack;
    }

    public long getEffectiveYearLengthInDays() {
        LunarEventConfigData config = ECConfigAccess.get();
        long datapack = this.dimensionSettingsHolder.value().yearLengthInDays();
        if (config != null && config.getYearLengthInDays() > 0) {
            return config.getYearLengthInDays();
        }
        return datapack;
    }

    private void createOrUpdateForecast(long lastCheckedDay) {
        long yearLengthInDays = getEffectiveYearLengthInDays();

        if (getCurrentDay() < lastCheckedDay - yearLengthInDays) {
            lastCheckedDay = getCurrentDay();
            setLastCheckedDay(lastCheckedDay);
        }

        if (this.forecast.isEmpty() && !this.lunarEventSpawnRequirements.isEmpty() && lastCheckedDay - getCurrentDay() >= yearLengthInDays) {
            EnhancedCelestials.LOGGER.warn("Forecast empty but lastCheckedDay ({}) is a full year ahead of current day ({}); resetting to rebuild forecast.", lastCheckedDay, getCurrentDay());
            lastCheckedDay = getCurrentDay();
            setLastCheckedDay(lastCheckedDay);
        }

        long dayDifference = clamp(lastCheckedDay - getCurrentDay(), 0L, yearLengthInDays);

        if (dayDifference < yearLengthInDays) {
            Object2LongArrayMap<ResourceKey<LunarEvent>> eventsByDay = eventsByDay();
            long lastScheduledEventDay = lastScheduledEventDay();
            long yearDayDifference = yearLengthInDays - dayDifference;
            for (int dayOffset = 0; dayOffset <= yearDayDifference; dayOffset++) {
                long day = getCurrentDay() + dayDifference + dayOffset;

                long seed = day + ((ServerLevel) level).getSeed() + level.dimension().hashCode();
                Random random = new Random(seed);

                List<Holder<LunarEvent>> scrambledLunarEvents = new ArrayList<>(this.lunarEventSpawnRequirements.keySet());
                Collections.shuffle(scrambledLunarEvents, random);

                for (Holder<LunarEvent> scrambledLunarEvent : scrambledLunarEvents) {
                    LunarEvent.SpawnRequirements spawnRequirements = this.lunarEventSpawnRequirements.get(scrambledLunarEvent);
                    boolean pastMinNumberOfNightsBetweenThisTypeOfEvent = (day - eventsByDay.getOrDefault(scrambledLunarEvent.unwrapKey().orElseThrow(), getCurrentDay())) > spawnRequirements.minNumberOfNights();
                    boolean pastMinNumberOfNightsBetweenAllEvents = (day - lastScheduledEventDay) > getEffectiveMinDaysBetweenEvents();
                    boolean isValidMoonPhase = spawnRequirements.validMoonPhases().contains(this.level.dimensionType().moonPhase(getDayTimeFromDay(day)));
                    boolean chance = spawnRequirements.chance() >= random.nextDouble();
                    boolean checksPass = pastMinNumberOfNightsBetweenThisTypeOfEvent && pastMinNumberOfNightsBetweenAllEvents && isValidMoonPhase && chance;

                    boolean override = !checksPass && lastScheduledEventDay != -1 && day - lastScheduledEventDay >= getEffectiveMaxDaysBetweenEvents();
                    if (checksPass || override) {
                        lastScheduledEventDay = day;
                        LunarEventInstance newLunarEventInstance = new LunarEventInstance(scrambledLunarEvent.unwrapKey().orElseThrow(), day);
                        eventsByDay.put(newLunarEventInstance.getLunarEventKey(), day);
                        forecast.add(newLunarEventInstance);
                    }
                }
            }
            setLastCheckedDay(getCurrentDay() + yearLengthInDays);
        }
    }

    public void setLastCheckedDay(long lastCheckedDay) {
        this.lastCheckedDay = lastCheckedDay;
        markChanged();
    }

    public void addEventToForecast(LunarEventInstance event) {
        this.forecast.add(event);
        markChanged();
    }

    public void addEventToForecast(int idx, LunarEventInstance event) {
        this.forecast.add(idx, event);
        markChanged();
    }

    public void removeEventInForecast(LunarEventInstance event) {
        this.forecast.remove(event);
        markChanged();
    }

    public void removeEventInForecast(int index) {
        this.forecast.remove(index);
        markChanged();
    }

    public void removeFromForecastIf(Predicate<LunarEventInstance> filter) {
        forecast.removeIf(lunarEventInstance -> {
            if (filter.test(lunarEventInstance)) {
                markChanged();
                return true;
            }


            return false;
        });
    }

    public void removeFromPastEventsIf(Predicate<LunarEventInstance> filter) {
        pastEvents.removeIf(lunarEventInstance -> {
            if (filter.test(lunarEventInstance)) {
                markChanged();
                return true;
            }
            return false;
        });
    }

    private void markChanged() {
        sync();
        markDirty();
    }

    @Override
    public void sync() {
        if (!level.isClientSide) {
            shouldSync = true;
        }
    }

    private void checkServer() {
        if (level.isClientSide) {
            throw new IllegalStateException("MUST BE CALLED FROM SERVER SIDE ONLY!");
        }
    }

    public LunarDimensionSettings getDimensionSettings() {
        return dimensionSettingsHolder.value();
    }

    public Holder<LunarDimensionSettings> getDimensionSettingsHolder() {
        return dimensionSettingsHolder;
    }

    public static long clamp(long value, long min, long max) {
        return value < min ? min : Math.min(value, max);
    }

    @Nullable
    public static EnhancedCelestialsLunarForecastWorldData factory(TrackedDataKey<EnhancedCelestialsLunarForecastWorldData> key, Level level) {
        ResourceKey<Level> dimension = level.dimension();
        ResourceLocation location = dimension.location();

        Registry<LunarDimensionSettings> lunarDimensionSettingsRegistry = level.registryAccess().registryOrThrow(EnhancedCelestialsRegistry.LUNAR_DIMENSION_SETTINGS_KEY);
        Optional<Holder.Reference<LunarDimensionSettings>> possibleLunarDimensionSettings = lunarDimensionSettingsRegistry.getHolder(ResourceKey.create(EnhancedCelestialsRegistry.LUNAR_DIMENSION_SETTINGS_KEY, location));

        if (possibleLunarDimensionSettings.isEmpty()) {
            return null;
        }
        Holder.Reference<LunarDimensionSettings> dimensionSettingsHolder = possibleLunarDimensionSettings.orElseThrow();

        Registry<LunarEvent> lunarEvents = level.registryAccess().registry(EnhancedCelestialsRegistry.LUNAR_EVENT_KEY).orElseThrow();
        final Object2ObjectOpenHashMap<Holder<LunarEvent>, LunarEvent.SpawnRequirements> lunarEventSpawnRequirements = createLunarEventSpawnRequirements(dimension, level.registryAccess().registryOrThrow(EnhancedCelestialsRegistry.LUNAR_EVENT_DIMENSION_CHANCE_KEY), lunarEvents, dimensionSettingsHolder);

        if (lunarEventSpawnRequirements.isEmpty()) {
            return null;
        }

        applyConfigOverrides(lunarEventSpawnRequirements, lunarEvents);

        return new EnhancedCelestialsLunarForecastWorldData(key, level, dimensionSettingsHolder, lunarEventSpawnRequirements);
    }

    private static Object2ObjectOpenHashMap<Holder<LunarEvent>, LunarEvent.SpawnRequirements> createLunarEventSpawnRequirements(ResourceKey<Level> dimension, Registry<LunarEventDimensionChance> lunarEventProbabilitiesRegistry, Registry<LunarEvent> lunarEvents, Holder.Reference<LunarDimensionSettings> dimensionSettingsHolder) {
        final Object2ObjectOpenHashMap<Holder<LunarEvent>, LunarEvent.SpawnRequirements> lunarEventSpawnRequirements = new Object2ObjectOpenHashMap<>();

        for (Map.Entry<ResourceKey<LunarEvent>, LunarEvent> resourceKeyLunarEventEntry : lunarEvents.entrySet()) {
            Holder<LunarEvent> lunarEventHolder = lunarEvents.getHolderOrThrow(resourceKeyLunarEventEntry.getKey());
            ResourceKey<Level> levelResourceKey = ResourceKey.create(Registries.DIMENSION, dimensionSettingsHolder.unwrapKey().orElseThrow().location());
            Map<ResourceKey<Level>, LunarEvent.SpawnRequirements> eventChancesByDimension = lunarEventHolder.value().getEventChancesByDimension();
            if (eventChancesByDimension.containsKey(levelResourceKey)) {
                LunarEvent.SpawnRequirements spawnRequirements = eventChancesByDimension.get(levelResourceKey);

                if (spawnRequirements.chance() > 0 && !spawnRequirements.validMoonPhases().isEmpty() && spawnRequirements.minNumberOfNights() >= 0) {
                    lunarEventSpawnRequirements.put(lunarEventHolder, spawnRequirements);
                }
            }
        }
        insertOverrides(lunarEventProbabilitiesRegistry, dimension, lunarEventSpawnRequirements, lunarEvents);
        return lunarEventSpawnRequirements;
    }

    private static void insertOverrides(Registry<LunarEventDimensionChance> lunarEventProbabilitiesRegistry, ResourceKey<Level> dimension, Object2ObjectOpenHashMap<Holder<LunarEvent>, LunarEvent.SpawnRequirements> lunarEventSpawnRequirements, Registry<LunarEvent> lunarEvents) {
        Object2ObjectRBTreeMap<ResourceKey<LunarEvent>, StringBuilder> loggerData = new Object2ObjectRBTreeMap<>(Comparator.comparing(ResourceKey::location));
        lunarEventProbabilitiesRegistry.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().priority(), a.getValue().priority())).forEachOrdered(lunarEventProbabilitiesEntry -> {
            ResourceKey<LunarEventDimensionChance> lunarEventProbabilitiesKey = lunarEventProbabilitiesEntry.getKey();
            LunarEventDimensionChance lunarEventDimensionChanceEntryValue = lunarEventProbabilitiesEntry.getValue();
            Map<ResourceKey<LunarEvent>, Map<ResourceKey<Level>, LunarEvent.SpawnRequirements>> lunarEventProbabilitiesByDimension = lunarEventDimensionChanceEntryValue.probabilitiesByEvent();

            lunarEventProbabilitiesByDimension.forEach((lunarEventResourceKey, value) -> {
                LunarEvent.SpawnRequirements spawnRequirementsOverride = value.get(dimension);
                if (spawnRequirementsOverride != null) {
                    if (!loggerData.containsKey(lunarEventResourceKey)) {
                        String s = "set";
                        if (lunarEventSpawnRequirements.containsKey(lunarEvents.getHolderOrThrow(lunarEventResourceKey))) {
                            s = "replaced";
                        }
                        loggerData.put(lunarEventResourceKey, new StringBuilder("[%s]: Lunar Event probability for \"%s\" was %s by highest priority lunar event probability \"%s\" with priority %d.".formatted(
                                dimension.location().toString(),
                                lunarEventResourceKey.location().toString(),
                                s,
                                lunarEventProbabilitiesKey.location().toString(),
                                lunarEventDimensionChanceEntryValue.priority()
                        )));

                        lunarEventSpawnRequirements.put(lunarEvents.getHolderOrThrow(lunarEventResourceKey), spawnRequirementsOverride);
                    } else {
                        StringBuilder builder = loggerData.get(lunarEventResourceKey);
                        if (!builder.toString().contains("Ignored")) {
                            builder.append(" | Ignored the following lunar event probabilities to due having a lower priority: ");
                        }

                        if (builder.toString().contains("[Probability")) {
                            builder.append(", ");
                        }

                        builder.append("[Probability=%s,Priority=%d]".formatted(
                                lunarEventProbabilitiesKey.location().toString(),
                                lunarEventDimensionChanceEntryValue.priority()
                        ));
                    }

                }
            });
        });
        loggerData.values().forEach(EnhancedCelestials.LOGGER::info);
    }
}