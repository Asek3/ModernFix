package org.embeddedt.modernfix.forge.dynresources;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.Sets;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.embeddedt.modernfix.dynamicresources.ModelLocationCache;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stores a list of all known default block/item models in the game, and provides a namespaced version
 * of the model registry that emulates vanilla keySet behavior.
 */
public class ModelBakeEventHelper {
    private final Map<ResourceLocation, BakedModel> modelRegistry;
    private final Set<ResourceLocation> topLevelModelLocations;
    private final MutableGraph<String> dependencyGraph;
    public ModelBakeEventHelper(Map<ResourceLocation, BakedModel> modelRegistry) {
        this.modelRegistry = modelRegistry;
        this.topLevelModelLocations = new HashSet<>(modelRegistry.keySet());
        for(Block block : ForgeRegistries.BLOCKS) {
            for(BlockState state : block.getStateDefinition().getPossibleStates()) {
                topLevelModelLocations.add(ModelLocationCache.get(state));
            }
        }
        for(Item item : ForgeRegistries.ITEMS) {
            topLevelModelLocations.add(ModelLocationCache.get(item));
        }
        this.dependencyGraph = GraphBuilder.undirected().build();
        ModList.get().forEachModContainer((id, mc) -> {
            this.dependencyGraph.addNode(id);
        });
        for(String id : this.dependencyGraph.nodes()) {
            Optional<? extends ModContainer> mContainer = ModList.get().getModContainerById(id);
            if(mContainer.isPresent()) {
                for(IModInfo.ModVersion version : mContainer.get().getModInfo().getDependencies()) {
                    this.dependencyGraph.putEdge(id, version.getModId());
                }
            }
        }
    }

    public Map<ResourceLocation, BakedModel> wrapRegistry(String modId) {
        final Set<String> modIdsToInclude = new HashSet<>();
        modIdsToInclude.add(modId);
        try {
            modIdsToInclude.addAll(this.dependencyGraph.adjacentNodes(modId));
        } catch(IllegalArgumentException ignored) { /* sanity check */ }
        modIdsToInclude.remove("minecraft");
        Set<ResourceLocation> ourModelLocations = Sets.filter(this.topLevelModelLocations, loc -> modIdsToInclude.contains(loc.getNamespace()));
        return new ForwardingMap<ResourceLocation, BakedModel>() {
            @Override
            protected Map<ResourceLocation, BakedModel> delegate() {
                return modelRegistry;
            }

            @Override
            public Set<ResourceLocation> keySet() {
                return ourModelLocations;
            }

            @Override
            public boolean containsKey(@Nullable Object key) {
                return ourModelLocations.contains(key) || super.containsKey(key);
            }
        };
    }
}
