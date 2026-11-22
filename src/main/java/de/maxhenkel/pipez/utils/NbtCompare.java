package de.maxhenkel.pipez.utils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * NBT 快速比较，带有尺寸提前退出，避免逐层遍历整棵树
 */
public final class NbtCompare {

    private NbtCompare() {
    }

    public static boolean exactCompare(Tag meta, Tag item) {
        if (meta instanceof CompoundTag c) {
            if (!(item instanceof CompoundTag i)) {
                return false;
            }
            if (c.isEmpty() && i.isEmpty()) {
                return true;
            }
            if (c.size() != i.size()) {
                return false;
            }
            for (String key : c.getAllKeys()) {
                if (!i.contains(key)) {
                    return false;
                }
                if (!exactCompare(c.get(key), i.get(key))) {
                    return false;
                }
            }
            return true;
        } else if (meta instanceof ListTag l) {
            if (!(item instanceof ListTag il)) {
                return false;
            }
            if (l.isEmpty() && il.isEmpty()) {
                return true;
            }
            for (Tag a : l) {
                if (!containsExact(il, a)) {
                    return false;
                }
            }
            for (Tag a : il) {
                if (!containsExact(l, a)) {
                    return false;
                }
            }
            return true;
        }
        return meta != null && meta.equals(item);
    }

    private static boolean containsExact(ListTag list, Tag value) {
        for (Tag b : list) {
            if (exactCompare(value, b)) {
                return true;
            }
        }
        return false;
    }

    public static boolean fuzzyCompare(Tag meta, Tag item) {
        if (meta instanceof CompoundTag c) {
            if (!(item instanceof CompoundTag i)) {
                return false;
            }
            if (c.size() > i.size()) {
                return false;
            }
            for (String key : c.getAllKeys()) {
                Tag nbt = c.get(key);
                if (!i.contains(key, nbt.getId())) {
                    return false;
                }
                if (!fuzzyCompare(nbt, i.get(key))) {
                    return false;
                }
            }
            return true;
        } else if (meta instanceof ListTag l) {
            if (!(item instanceof ListTag il)) {
                return false;
            }
            for (Tag a : l) {
                boolean anyMatch = false;
                for (Tag b : il) {
                    if (fuzzyCompare(a, b)) {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) {
                    return false;
                }
            }
            return true;
        }
        return meta != null && meta.equals(item);
    }
}
