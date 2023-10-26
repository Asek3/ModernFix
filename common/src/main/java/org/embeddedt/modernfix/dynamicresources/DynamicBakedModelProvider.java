package org.embeddedt.modernfix.dynamicresources;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Triple;
import org.embeddedt.modernfix.ModernFix;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class DynamicBakedModelProvider implements Map<ResourceLocation, BakedModel> {
    /**
     * The list of blacklisted resource locations that are never baked as top-level models.
     *
     * This is a hack to get around the fact that we don't really know exactly what models were supposed to end up
     * in the baked registry ahead of time.
     */
    private static final ImmutableSet<ResourceLocation> BAKE_SKIPPED_TOPLEVEL = ImmutableSet.<ResourceLocation>builder()
            .add(new ResourceLocation("custommachinery", "block/custom_machine_block"))
            .build();
    public static DynamicBakedModelProvider currentInstance = null;
    private final ModelBakery bakery;
    private final Map<Triple<ResourceLocation, Transformation, Boolean>, BakedModel> bakedCache;
    private final Map<ResourceLocation, ModelHolder> permanentOverrides;
    private BakedModel missingModel;

    static class ModelHolder {
        public final BakedModel model;

        ModelHolder(BakedModel model) {
            this.model = model;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModelHolder that = (ModelHolder) o;
            return Objects.equals(model, that.model);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(model);
        }
    }

    public DynamicBakedModelProvider(ModelBakery bakery, Map<Triple<ResourceLocation, Transformation, Boolean>, BakedModel> cache) {
        this.bakery = bakery;
        this.bakedCache = cache;
        this.permanentOverrides = new ConcurrentHashMap<>();
        if(currentInstance == null)
            currentInstance = this;
    }

    public void setMissingModel(BakedModel model) {
        this.missingModel = model;
    }

    private static Triple<ResourceLocation, Transformation, Boolean> vanillaKey(Object o) {
        return Triple.of((ResourceLocation)o, BlockModelRotation.X0_Y0.getRotation(), false);
    }
    @Override
    public int size() {
        return bakedCache.size();
    }

    @Override
    public boolean isEmpty() {
        return bakedCache.isEmpty();
    }

    @Override
    public boolean containsKey(Object o) {
        ModelHolder holder = permanentOverrides.get(o);
        if(holder == null)
            return true; // lie, because we can probably load the model
        return holder.model != null; // if null, there was an error, which means we should pretend it isn't loaded
    }

    @Override
    public boolean containsValue(Object o) {
        return permanentOverrides.values().stream().anyMatch(holder -> holder != null && holder.model == o) || bakedCache.containsValue(o);
    }
    
    private static boolean isVanillaTopLevelModel(ResourceLocation location) {
        if(location instanceof ModelResourceLocation) {
            try {
                ModelResourceLocation mrl = (ModelResourceLocation)location;
                ResourceLocation registryKey = new ResourceLocation(mrl.getNamespace(), mrl.getPath());
                // check for standard inventory model
                if(mrl.getVariant().equals("inventory") && Registry.ITEM.containsKey(registryKey))
                    return true;
                Optional<Block> blockOpt = Registry.BLOCK.getOptional(registryKey);
                if(blockOpt.isPresent()) {
                    return ModelBakeryHelpers.getBlockStatesForMRL(blockOpt.get().getStateDefinition(), mrl).size() > 0;
                }
            } catch(RuntimeException ignored) {
                // can occur if the MRL is not valid for that blockstate, ignore
            }
        }
        if(location.getNamespace().equals("minecraft") && location.getPath().equals("builtin/missing"))
            return true;
        return false;
    }

    @Override
    public BakedModel get(Object o) {
        ModelHolder holder = permanentOverrides.get(o);
        if(holder != null)
            return holder.model;
        else {
            BakedModel model;
            try {
                if(BAKE_SKIPPED_TOPLEVEL.contains((ResourceLocation)o))
                    model = missingModel;
                else
                    model = bakery.bake((ResourceLocation)o, BlockModelRotation.X0_Y0);
            } catch(RuntimeException e) {
                ModernFix.LOGGER.error("Exception baking {}: {}", o, e);
                model = missingModel;
            }
            if(model == missingModel) {
                // to correctly emulate the original map, we return null for missing models, unless they are top-level
                model = isVanillaTopLevelModel((ResourceLocation)o) ? model : null;
                permanentOverrides.put((ResourceLocation) o, new ModelHolder(model));
            }
            return model;
        }
    }

    @Override
    public BakedModel put(ResourceLocation resourceLocation, BakedModel bakedModel) {
        ModelHolder m = permanentOverrides.put(resourceLocation, new ModelHolder(bakedModel));
        if(m != null)
            return m.model;
        else
            return bakedCache.get(vanillaKey(resourceLocation));
    }

    @Override
    public BakedModel remove(Object o) {
        ModelHolder m = permanentOverrides.remove(o);
        if(m != null)
            return m.model;
        return bakedCache.remove(vanillaKey(o));
    }

    @Override
    public void putAll(@NotNull Map<? extends ResourceLocation, ? extends BakedModel> map) {
        permanentOverrides.putAll(Maps.transformValues(map, ModelHolder::new));
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Set<ResourceLocation> keySet() {
        return bakedCache.keySet().stream().map(Triple::getLeft).collect(Collectors.toSet());
    }

    @NotNull
    @Override
    public Collection<BakedModel> values() {
        return bakedCache.values();
    }

    @NotNull
    @Override
    public Set<Entry<ResourceLocation, BakedModel>> entrySet() {
        return bakedCache.entrySet().stream().map(entry -> new AbstractMap.SimpleImmutableEntry<>(entry.getKey().getLeft(), entry.getValue())).collect(Collectors.toSet());
    }

    @Nullable
    @Override
    public BakedModel replace(ResourceLocation key, BakedModel value) {
        ModelHolder existingOverride = permanentOverrides.get(key);
        // as long as no valid override was put in (null can mean unable to load model, so we treat as invalid), replace
        // the model
        if(existingOverride == null || existingOverride.model == null)
            return this.put(key, value);
        else
            return existingOverride.model;
    }

    @Override
    public void replaceAll(BiFunction<? super ResourceLocation, ? super BakedModel, ? extends BakedModel> function) {
        Set<ResourceLocation> overridenLocations = permanentOverrides.keySet();
        permanentOverrides.replaceAll((key, holder) -> new ModelHolder(function.apply(key, holder.model)));
        boolean uvLock = BlockModelRotation.X0_Y0.isUvLocked();
        Transformation rotation = BlockModelRotation.X0_Y0.getRotation();
        bakedCache.replaceAll((loc, oldModel) -> {
            if(loc.getMiddle() != rotation || loc.getRight() != uvLock || overridenLocations.contains(loc.getLeft()))
                return oldModel;
            else
                return function.apply(loc.getLeft(), oldModel);
        });
    }
}
