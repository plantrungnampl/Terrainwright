package dev.ssa.fabric.style;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import dev.ssa.fabric.TerrainwrightMod;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads only data-driven palettes; geometry remains in the trusted built-in style implementations. */
public final class StyleDataLoader implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainwrightMod.MOD_ID + "/styles");
    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath(
            TerrainwrightMod.MOD_ID, "style_palettes");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "formatVersion",
            "id",
            "displayName",
            "version",
            "geometryRules",
            "proportionRules",
            "footprintWeights",
            "foundationRules",
            "framingRules",
            "roofRules",
            "openingRules",
            "roomBiases",
            "decorationRules",
            "materialPalette",
            "fallbackPalette");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "blockId", "requiredCapabilities", "weight", "stateTemplate");
    private static final Map<StyleId, StylePack> BUILT_INS = builtIns();
    private static volatile Map<StyleId, LoadedStyle> loaded = Map.of();
    private static boolean initialized;

    private StyleDataLoader() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        loaded = builtInSnapshot();
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(RELOAD_ID, new StyleDataLoader());
    }

    public static Optional<LoadedStyle> find(StyleId styleId) {
        Objects.requireNonNull(styleId, "styleId");
        LoadedStyle result = loaded.get(styleId);
        if (result != null) {
            return Optional.of(result);
        }
        StylePack builtIn = BUILT_INS.get(styleId);
        return builtIn == null ? Optional.empty() : Optional.of(loadBuiltIn(builtIn));
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<Identifier, Resource> resources = manager.listResources(
                "styles",
                id -> id.getNamespace().equals(TerrainwrightMod.MOD_ID)
                        && id.getPath().endsWith(".json"));
        Map<StyleId, LoadedStyle> next = new HashMap<>();
        for (StylePack builtIn : BUILT_INS.values()) {
            Identifier resourceId = Identifier.fromNamespaceAndPath(
                    TerrainwrightMod.MOD_ID,
                    "styles/" + builtIn.id().value().path() + ".json");
            Resource resource = resources.get(resourceId);
            if (resource == null) {
                LOGGER.error("Style data {} is missing; retaining trusted built-in palette", resourceId);
                next.put(builtIn.id(), loadBuiltIn(builtIn));
                continue;
            }
            try (var reader = resource.openAsReader()) {
                LoadedStyle parsed = parse(builtIn, JsonParser.parseReader(reader).getAsJsonObject());
                next.put(builtIn.id(), parsed);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error(
                        "Rejected style data {} from {}; retaining trusted built-in palette",
                        resourceId,
                        resource.sourcePackId(),
                        exception);
                next.put(builtIn.id(), loadBuiltIn(builtIn));
            }
        }
        loaded = Map.copyOf(next);
    }

    static LoadedStyle parse(StylePack builtIn, JsonObject document) {
        Objects.requireNonNull(builtIn, "builtIn");
        Objects.requireNonNull(document, "document");
        requireExactFields(document, TOP_LEVEL_FIELDS, "style pack");
        if (requireInt(document, "formatVersion") != 1) {
            throw new IllegalArgumentException("Style formatVersion must be 1");
        }
        StyleId documentId = StyleId.parse(requireString(document, "id"));
        if (!builtIn.id().equals(documentId)) {
            throw new IllegalArgumentException("Style data id does not match its trusted generator");
        }
        if (requireString(document, "displayName").isBlank()) {
            throw new IllegalArgumentException("Style displayName must not be blank");
        }
        requireString(document, "version");
        JsonObject geometryRules = requireObject(document, "geometryRules");
        String expectedProfile = builtIn.id().value().path().toUpperCase(java.util.Locale.ROOT);
        if (!expectedProfile.equals(requireString(geometryRules, "trustedGeneratorProfile"))) {
            throw new IllegalArgumentException("Style data cannot replace its trusted geometry profile");
        }
        requireObject(document, "proportionRules");
        requireObject(document, "footprintWeights");
        requireObject(document, "foundationRules");
        requireObject(document, "framingRules");
        requireObject(document, "roofRules");
        requireObject(document, "openingRules");
        requireObject(document, "roomBiases");
        requireObject(document, "decorationRules");

        Map<MaterialRole, List<StylePack.PaletteCandidate>> overrides = parsePalette(
                builtIn, requireObject(document, "materialPalette"), false);
        Map<MaterialRole, List<StylePack.PaletteCandidate>> fallback = parsePalette(
                builtIn, requireObject(document, "fallbackPalette"), true);
        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> combined = new EnumMap<>(MaterialRole.class);
        for (MaterialRole role : MaterialRole.values()) {
            List<StylePack.PaletteCandidate> candidates = new ArrayList<>();
            candidates.addAll(overrides.getOrDefault(role, List.of()));
            candidates.addAll(fallback.get(role));
            combined.put(role, List.copyOf(candidates));
        }
        StylePack style = new PaletteBackedStyle(builtIn, StylePack.immutablePalette(combined));
        return new LoadedStyle(style, capabilityRegistry(combined));
    }

    private static Map<MaterialRole, List<StylePack.PaletteCandidate>> parsePalette(
            StylePack builtIn,
            JsonObject palette,
            boolean completeFallback) {
        EnumMap<MaterialRole, JsonElement> declared = new EnumMap<>(MaterialRole.class);
        for (Map.Entry<String, JsonElement> entry : palette.entrySet()) {
            MaterialRole role;
            try {
                role = MaterialRole.valueOf(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown canonical material role: " + entry.getKey(), exception);
            }
            declared.put(role, entry.getValue());
        }
        if (completeFallback && !declared.keySet().equals(Set.of(MaterialRole.values()))) {
            throw new IllegalArgumentException("Fallback palette must cover every canonical material role");
        }
        if (!completeFallback && declared.isEmpty()) {
            throw new IllegalArgumentException("materialPalette must contain at least one canonical role");
        }

        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> result = new EnumMap<>(MaterialRole.class);
        for (Map.Entry<MaterialRole, JsonElement> entry : declared.entrySet()) {
            MaterialRole role = entry.getKey();
            JsonArray candidates = requireArray(entry.getValue(), "palette role " + role);
            if (candidates.isEmpty() || candidates.size() > 16) {
                throw new IllegalArgumentException("Palette role " + role + " must contain 1..16 candidates");
            }
            List<StylePack.PaletteCandidate> accepted = new ArrayList<>();
            for (JsonElement element : candidates) {
                Optional<StylePack.PaletteCandidate> candidate = parseCandidate(
                        builtIn, role, requireObject(element, "palette candidate"), completeFallback);
                candidate.ifPresent(accepted::add);
            }
            if (completeFallback && accepted.isEmpty()) {
                throw new IllegalArgumentException("Fallback role " + role + " has no compatible block");
            }
            result.put(role, List.copyOf(accepted));
        }
        return Map.copyOf(result);
    }

    private static Optional<StylePack.PaletteCandidate> parseCandidate(
            StylePack builtIn,
            MaterialRole role,
            JsonObject candidate,
            boolean requiredFallback) {
        requireExactFields(candidate, CANDIDATE_FIELDS, "palette candidate");
        NamespacedId blockId = NamespacedId.parse(requireString(candidate, "blockId"));
        double weight = requireDouble(candidate, "weight");
        if (!Double.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("Palette weight must be finite and between 0 and 1");
        }
        JsonArray capabilityValues = requireArray(candidate.get("requiredCapabilities"), "requiredCapabilities");
        if (capabilityValues.size() > 8) {
            throw new IllegalArgumentException("Palette candidate declares too many capabilities");
        }
        Set<BlockCapability> declaredCapabilities = new HashSet<>();
        for (JsonElement value : capabilityValues) {
            BlockCapability capability = BlockCapability.parse(requireString(value, "requiredCapabilities entry"));
            if (!declaredCapabilities.add(capability)) {
                throw new IllegalArgumentException("Palette capabilities must be unique");
            }
        }
        Set<BlockCapability> required = new HashSet<>(builtIn.requiredCapabilities(role));
        required.addAll(declaredCapabilities);
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId.toString())).orElse(null);
        if (block == null) {
            if (requiredFallback) {
                throw new IllegalArgumentException("Required fallback block is absent: " + blockId);
            }
            return Optional.empty();
        }
        JsonObject template = requireObject(candidate, "stateTemplate");
        if (template.size() > 24) {
            throw new IllegalArgumentException("Palette stateTemplate has too many properties");
        }
        Map<String, String> properties = new HashMap<>();
        template.entrySet().forEach(entry -> properties.put(entry.getKey(), requireString(entry.getValue(), entry.getKey())));
        BlockStateSpec specification = new BlockStateSpec(blockId, properties);
        BlockState state;
        try {
            state = applyProperties(block.defaultBlockState(), properties);
        } catch (IllegalArgumentException exception) {
            if (requiredFallback) {
                throw exception;
            }
            return Optional.empty();
        }
        Set<BlockCapability> actual = actualCapabilities(state);
        BlockCapabilityRegistry candidateRegistry = BlockCapabilityRegistry.of(Map.of(blockId, actual));
        if (!actual.containsAll(required) || !candidateRegistry.supports(specification)) {
            if (requiredFallback) {
                throw new IllegalArgumentException(
                        "Fallback block " + blockId + " is incompatible with " + required + " and " + properties);
            }
            return Optional.empty();
        }
        return Optional.of(new StylePack.PaletteCandidate(specification, required));
    }

    private static LoadedStyle loadBuiltIn(StylePack builtIn) {
        return new LoadedStyle(builtIn, capabilityRegistry(builtIn.fallbackPalette()));
    }

    private static Map<StyleId, LoadedStyle> builtInSnapshot() {
        Map<StyleId, LoadedStyle> result = new HashMap<>();
        BUILT_INS.forEach((id, style) -> result.put(id, loadBuiltIn(style)));
        return Map.copyOf(result);
    }

    private static Map<StyleId, StylePack> builtIns() {
        StylePack medieval = new MedievalStyle();
        StylePack japanese = new JapaneseStyle();
        StylePack modern = new ModernStyle();
        return Map.of(medieval.id(), medieval, japanese.id(), japanese, modern.id(), modern);
    }

    private static BlockCapabilityRegistry capabilityRegistry(
            Map<MaterialRole, ? extends List<StylePack.PaletteCandidate>> palette) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        palette.values().forEach(candidates -> candidates.forEach(candidate -> BuiltInRegistries.BLOCK
                .getOptional(Identifier.parse(candidate.state().blockId().toString()))
                .ifPresent(block -> entries.merge(
                        candidate.state().blockId(),
                        actualCapabilities(applyProperties(
                                block.defaultBlockState(), candidate.state().properties())),
                        StyleDataLoader::unionCapabilities))));
        return BlockCapabilityRegistry.of(entries);
    }

    private static Set<BlockCapability> unionCapabilities(
            Set<BlockCapability> first, Set<BlockCapability> second) {
        Set<BlockCapability> union = new HashSet<>(first);
        union.addAll(second);
        return Set.copyOf(union);
    }

    private static Set<BlockCapability> actualCapabilities(BlockState state) {
        Set<BlockCapability> capabilities = new HashSet<>();
        Block block = state.getBlock();
        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            capabilities.add(BlockCapability.FULL_CUBE);
        }
        if (block instanceof StairBlock) {
            capabilities.add(BlockCapability.STAIR);
        }
        if (block instanceof SlabBlock) {
            capabilities.add(BlockCapability.SLAB);
        }
        if (block instanceof IronBarsBlock) {
            capabilities.add(BlockCapability.PANE);
        }
        if (block instanceof DoorBlock) {
            capabilities.add(BlockCapability.DOOR);
        }
        if (block instanceof TrapDoorBlock) {
            capabilities.add(BlockCapability.TRAPDOOR);
        }
        if (block instanceof FenceBlock) {
            capabilities.add(BlockCapability.FENCE);
            capabilities.add(BlockCapability.FENCE_OR_WALL);
        }
        if (block instanceof WallBlock) {
            capabilities.add(BlockCapability.FENCE_OR_WALL);
        }
        if (state.getLightEmission() > 0) {
            capabilities.add(BlockCapability.LIGHT_SOURCE);
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            capabilities.add(BlockCapability.ORIENTABLE_AXIS);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            capabilities.add(BlockCapability.HORIZONTAL_FACING);
        }
        return Set.copyOf(capabilities);
    }

    private static BlockState applyProperties(BlockState state, Map<String, String> properties) {
        BlockState result = state;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = result.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("Unknown block property " + entry.getKey());
            }
            result = setProperty(result, property, entry.getValue());
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(
                () -> new IllegalArgumentException("Invalid value " + value + " for property " + property.getName()));
        return state.setValue(property, parsed);
    }

    private static void requireExactFields(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException(label + " fields must be exactly " + expected);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return requireObject(value, name);
    }

    private static JsonObject requireObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement value, String label) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String name) {
        return requireString(object.get(name), name);
    }

    private static String requireString(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return value.getAsString();
    }

    private static int requireInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.getAsInt();
    }

    private static double requireDouble(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return value.getAsDouble();
    }

    public record LoadedStyle(StylePack style, BlockCapabilityRegistry capabilities) {
        public LoadedStyle {
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    private record PaletteBackedStyle(
            StylePack delegate,
            Map<MaterialRole, List<StylePack.PaletteCandidate>> fallbackPalette) implements StylePack {
        private PaletteBackedStyle {
            Objects.requireNonNull(delegate, "delegate");
            fallbackPalette = StylePack.immutablePalette(fallbackPalette);
        }

        @Override
        public StyleId id() {
            return delegate.id();
        }

        @Override
        public String displayName() {
            return delegate.displayName();
        }

        @Override
        public int version() {
            return delegate.version();
        }

        @Override
        public ProportionRules proportionRules() {
            return delegate.proportionRules();
        }

        @Override
        public FoundationRules foundationRules() {
            return delegate.foundationRules();
        }

        @Override
        public RoofRules roofRules() {
            return delegate.roofRules();
        }

        @Override
        public OpeningRules openingRules() {
            return delegate.openingRules();
        }
    }
}
