package de.maxhenkel.pipez.utils;

import com.mojang.datafixers.util.Pair;
import de.maxhenkel.corelib.tag.Tag;
import de.maxhenkel.pipez.Filter;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 过滤器单次遍历匹配，结合过滤器自身的最后匹配缓存与组件补丁快速路径
 */
public final class FilterMatcher {

    private FilterMatcher() {
    }

    public static boolean canInsertItem(PipeType pipeType, HolderLookup.Provider provider, PipeTileEntity.Connection connection, ItemStack stack, List<Filter<?, ?>> filters) {
        return canInsert(pipeType, connection, filters, filter -> matchesItem(provider, filter, stack));
    }

    public static boolean canInsertFluid(PipeType pipeType, HolderLookup.Provider provider, PipeTileEntity.Connection connection, FluidStack stack, List<Filter<?, ?>> filters) {
        return canInsert(pipeType, connection, filters, filter -> matchesFluid(provider, filter, stack));
    }

    public static boolean canInsertGas(PipeType pipeType, PipeTileEntity.Connection connection, ChemicalStack stack, List<Filter<?, ?>> filters) {
        return canInsert(pipeType, connection, filters, filter -> {
            Tag tag = filter.getTag();
            return tag == null || tag.contains(stack.getChemical());
        });
    }

    private static boolean canInsert(PipeType pipeType, PipeTileEntity.Connection connection, List<Filter<?, ?>> filters, Predicate<Filter> matcher) {
        boolean hasNormal = false;
        boolean normalMatched = false;
        for (Filter filter : filters) {
            if (!pipeType.matchesConnection(connection, filter)) {
                continue;
            }
            boolean matches = matcher.test(filter);
            if (filter.isInvert()) {
                if (matches) {
                    return false;
                }
            } else {
                hasNormal = true;
                if (matches) {
                    normalMatched = true;
                }
            }
        }
        return !hasNormal || normalMatched;
    }

    public static boolean matchesItem(HolderLookup.Provider provider, Filter filter, ItemStack stack) {
        CompoundTag metadata = filter.getMetadata();
        if (metadata == null) {
            Tag tag = filter.getTag();
            return tag == null || tag.contains(stack.getItem());
        }
        if (filter.hasItemMatchCache(stack)) {
            return filter.getItemMatchResult();
        }
        boolean result = matchesItemUncached(provider, filter, metadata, stack);
        filter.setItemMatchCache(stack, result);
        return result;
    }

    private static boolean matchesItemUncached(HolderLookup.Provider provider, Filter filter, CompoundTag metadata, ItemStack stack) {
        Tag tag = filter.getTag();
        if (filter.isExactMetadata()) {
            if (tag != null && !tag.contains(stack.getItem())) {
                return false;
            }
            if (matchesExactPatch(filter, stack.getComponentsPatch())) {
                return true;
            }
            CompoundTag stackNBT = getComponentTag(provider, stack);
            if (!NbtCompare.exactCompare(metadata, stackNBT)) {
                return false;
            }
            return tag == null || tag.contains(stack.getItem());
        }
        if (tag != null && !tag.contains(stack.getItem())) {
            return false;
        }
        CompoundTag stackNBT = getComponentTag(provider, stack);
        if (stackNBT.isEmpty()) {
            return metadata.size() <= 0;
        }
        if (stackNBT.size() < metadata.size()) {
            return false;
        }
        if (!NbtCompare.fuzzyCompare(metadata, stackNBT)) {
            return false;
        }
        return tag == null || tag.contains(stack.getItem());
    }

    public static boolean matchesFluid(HolderLookup.Provider provider, Filter filter, FluidStack stack) {
        CompoundTag metadata = filter.getMetadata();
        if (metadata == null) {
            Tag tag = filter.getTag();
            return tag == null || tag.contains(stack.getFluid());
        }
        if (filter.hasFluidMatchCache(stack)) {
            return filter.getFluidMatchResult();
        }
        boolean result = matchesFluidUncached(provider, filter, metadata, stack);
        filter.setFluidMatchCache(stack, result);
        return result;
    }

    private static boolean matchesFluidUncached(HolderLookup.Provider provider, Filter filter, CompoundTag metadata, FluidStack stack) {
        Tag tag = filter.getTag();
        if (filter.isExactMetadata()) {
            if (tag != null && !tag.contains(stack.getFluid())) {
                return false;
            }
            if (matchesExactPatch(filter, stack.getComponentsPatch())) {
                return true;
            }
            CompoundTag stackNBT = getComponentTag(provider, stack);
            if (!NbtCompare.exactCompare(metadata, stackNBT)) {
                return false;
            }
            return tag == null || tag.contains(stack.getFluid());
        }
        if (tag != null && !tag.contains(stack.getFluid())) {
            return false;
        }
        CompoundTag stackNBT = getComponentTag(provider, stack);
        if (stackNBT.isEmpty()) {
            return metadata.size() <= 0;
        }
        if (stackNBT.size() < metadata.size()) {
            return false;
        }
        if (!NbtCompare.fuzzyCompare(metadata, stackNBT)) {
            return false;
        }
        return tag == null || tag.contains(stack.getFluid());
    }

    private static boolean matchesExactPatch(Filter filter, DataComponentPatch stackPatch) {
        DataComponentPatch patch = filter.getExactPatch();
        return patch != null && stackPatch.size() == patch.size() && stackPatch.equals(patch);
    }

    private static CompoundTag getComponentTag(HolderLookup.Provider provider, ItemStack stack) {
        if (stack.isComponentsPatchEmpty()) {
            return new CompoundTag();
        }
        return ComponentUtils.getTag(provider, stack);
    }

    private static CompoundTag getComponentTag(HolderLookup.Provider provider, FluidStack stack) {
        if (stack.isComponentsPatchEmpty()) {
            return new CompoundTag();
        }
        return ComponentUtils.getTag(provider, stack);
    }

    @Nullable
    public static DataComponentPatch decodePatch(HolderLookup.Provider provider, CompoundTag metadata) {
        return DataComponentPatch.CODEC.decode(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), metadata)
                .result()
                .map(Pair::getFirst)
                .orElse(null);
    }

    @Nullable
    public static CompoundTag encodePatch(HolderLookup.Provider provider, DataComponentPatch patch) {
        return (CompoundTag) DataComponentPatch.CODEC.encodeStart(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), patch)
                .result()
                .orElse(null);
    }
}
