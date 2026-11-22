package de.maxhenkel.pipez.blocks.tileentity;

import de.maxhenkel.pipez.blocks.tileentity.types.EnergyPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.FluidPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.ItemPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import de.maxhenkel.pipez.utils.DummyFluidHandler;
import de.maxhenkel.pipez.utils.DummyItemHandler;
import de.maxhenkel.pipez.utils.PipeEnergyStorage;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;

public abstract class PipeLogicTileEntity extends UpgradeTileEntity {

    protected PipeType<?, ?>[] types;
    protected final int[][] rrIndex;

    protected PipeEnergyStorage[] energyStorages;

    private int recursionDepth;

    private final long[] nextRunTime;
    private final int[] failurePenalty;
    private final int[] lastSlot;
    private final IdentityHashMap<PipeType<?, ?>, Integer> pipeTypeIndexCache;
    @Nullable
    private Object2LongOpenHashMap<BlockPos>[] destinationSleepTimes;
    @Nullable
    private Object2IntOpenHashMap<BlockPos>[] destinationPenalties;

    public PipeLogicTileEntity(BlockEntityType<?> tileEntityTypeIn, PipeType<?, ?>[] types, BlockPos pos, BlockState state) {
        super(tileEntityTypeIn, pos, state);
        this.types = types;
        rrIndex = new int[Direction.values().length][types.length];
        energyStorages = new PipeEnergyStorage[Direction.values().length];
        nextRunTime = new long[FREQUENCY_SLOTS];
        failurePenalty = new int[FREQUENCY_SLOTS];
        lastSlot = new int[FREQUENCY_SLOTS];
        pipeTypeIndexCache = new IdentityHashMap<>();
        for (int i = 0; i < types.length; i++) {
            pipeTypeIndexCache.put(types[i], i);
        }
        for (Direction side : Direction.values()) {
            for (int i = 0; i < types.length; i++) {
                recalculateEffectiveFrequency(side, i);
            }
        }
    }

    @Nullable
    public <T> T onRegisterCapability(BlockCapability<T, Direction> capability, @Nullable Direction side) {
        if (side == null) {
            return null;
        }
        if (!isExtracting(side)) {
            return null;
        }
        if (capability == Capabilities.EnergyStorage.BLOCK && hasType(EnergyPipeType.INSTANCE)) {
            if (side != null) {
                if (energyStorages[side.get3DDataValue()] == null) {
                    energyStorages[side.get3DDataValue()] = new PipeEnergyStorage(this, side);
                }
                return (T) energyStorages[side.get3DDataValue()];
            }
        } else if (capability == Capabilities.FluidHandler.BLOCK && hasType(FluidPipeType.INSTANCE)) {
            if (side != null) {
                return (T) DummyFluidHandler.INSTANCE;
            }
        } else if (capability == Capabilities.ItemHandler.BLOCK && hasType(ItemPipeType.INSTANCE)) {
            if (side != null) {
                return (T) DummyItemHandler.INSTANCE;
            }
        }
        return null;
    }

    public boolean hasType(PipeType<?, ?> type) {
        for (PipeType<?, ?> t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    public int getRoundRobinIndex(Direction direction, PipeType<?, ?> pipeType) {
        return rrIndex[direction.get3DDataValue()][getIndex(pipeType)];
    }

    public void setRoundRobinIndex(Direction direction, PipeType<?, ?> pipeType, int value) {
        rrIndex[direction.get3DDataValue()][getIndex(pipeType)] = value;
    }

    public boolean isEnabled(Direction side, PipeType<?, ?> pipeType) {
        UpgradeTileEntity.RedstoneMode redstoneMode = getRedstoneMode(side, pipeType);
        return redstoneMode != UpgradeTileEntity.RedstoneMode.ALWAYS_OFF;
    }

    public int getPreferredPipeIndex(Direction side) {
        for (int i = 0; i < types.length; i++) {
            if (isEnabled(side, types[i])) {
                return i;
            }
        }
        return 0;
    }

    public boolean shouldWork(Direction side, PipeType<?, ?> pipeType) {
        if (level != null && !level.isClientSide) {
            int index = frequencySlot(side, getIndexCached(pipeType));
            if (index >= 0 && level.getGameTime() < nextRunTime[index]) {
                return false;
            }
        }
        RedstoneMode redstoneMode = getRedstoneMode(side, pipeType);
        if (redstoneMode.equals(RedstoneMode.ALWAYS_OFF)) {
            return false;
        } else if (redstoneMode.equals(RedstoneMode.OFF_WHEN_POWERED)) {
            return !isRedstonePowered();
        } else if (redstoneMode.equals(RedstoneMode.ON_WHEN_POWERED)) {
            return isRedstonePowered();
        } else {
            return true;
        }
    }

    public boolean isRedstonePowered() {
        return level.hasNeighborSignal(worldPosition);
    }

    public PipeType<?, ?>[] getPipeTypes() {
        return types;
    }

    public int getIndex(PipeType<?, ?> pipeType) {
        for (int i = 0; i < getPipeTypes().length; i++) {
            PipeType<?, ?> type = getPipeTypes()[i];
            if (type == pipeType) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 带缓存的管道类型索引查询
     */
    public int getIndexCached(PipeType<?, ?> pipeType) {
        Integer cached = pipeTypeIndexCache.get(pipeType);
        return cached != null ? cached : getIndex(pipeType);
    }

    /**
     * 传输成功后按生效频率安排下一次运行
     */
    public void markExtractionSuccessful(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0 || level == null) {
            return;
        }
        failurePenalty[index] = 0;
        nextRunTime[index] = level.getGameTime() + getEffectiveFrequency(side, pipeIndex);
    }

    /**
     * 传输失败后按指数退避安排下一次运行，上限为该类型的最大休眠时长
     */
    public void addExtractionPenalty(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        if (isSmartSleepDisabled(side, pipeIndex)) {
            if (level != null) {
                nextRunTime[index] = level.getGameTime() + Math.max(1, getEffectiveFrequency(side, pipeIndex));
            }
            return;
        }
        int maxSleep = getMaxSleepTicks(pipeIndex);
        if (maxSleep <= 0) {
            if (level != null) {
                nextRunTime[index] = level.getGameTime() + Math.max(1, getEffectiveFrequency(side, pipeIndex));
            }
            return;
        }
        int frequency = Math.max(1, getEffectiveFrequency(side, pipeIndex));
        if (frequency >= maxSleep) {
            failurePenalty[index] = frequency;
            nextRunTime[index] = level.getGameTime() + frequency;
            return;
        }
        int currentPenalty = failurePenalty[index];
        if (currentPenalty >= maxSleep) {
            nextRunTime[index] = level.getGameTime() + maxSleep;
            return;
        }
        if (currentPenalty == 0) {
            currentPenalty = frequency;
        }
        int newPenalty = Math.min(maxSleep, Math.max(currentPenalty + 1, currentPenalty * 136 / 100));
        failurePenalty[index] = newPenalty;
        nextRunTime[index] = level.getGameTime() + newPenalty;
    }

    /**
     * 单个目标休眠时间到点前的唤醒检查
     */
    public boolean isDestinationAwake(Direction side, BlockPos destinationPos, int pipeIndex) {
        if (level == null || pipeIndex < 0 || pipeIndex > 5) {
            return true;
        }
        if (isSmartSleepDisabled(side, pipeIndex)) {
            return true;
        }
        if (destinationSleepTimes == null) {
            return true;
        }
        return level.getGameTime() >= destinationSleepTimes[pipeIndex].getLong(destinationPos);
    }

    /**
     * 标记单个目标失败并进入目标级休眠退避
     */
    public void markDestinationPenalty(Direction side, BlockPos destinationPos, int pipeIndex) {
        if (level == null || pipeIndex < 0 || pipeIndex > 5) {
            return;
        }
        if (isSmartSleepDisabled(side, pipeIndex)) {
            return;
        }
        int maxPenalty = getMaxSleepTicks(pipeIndex);
        if (maxPenalty <= 0) {
            return;
        }
        Object2IntOpenHashMap<BlockPos> penaltyMap = destinationPenaltyMaps()[pipeIndex];
        int currentPenalty = penaltyMap.getInt(destinationPos);
        if (currentPenalty >= maxPenalty) {
            destinationSleepMaps()[pipeIndex].put(destinationPos, level.getGameTime() + maxPenalty);
            return;
        }
        int newPenalty = currentPenalty == 0 ? 2 : currentPenalty * 136 / 100;
        if (newPenalty < currentPenalty + 1) {
            newPenalty = currentPenalty + 1;
        }
        if (newPenalty > maxPenalty) {
            newPenalty = maxPenalty;
        }
        penaltyMap.put(destinationPos, newPenalty);
        destinationSleepMaps()[pipeIndex].put(destinationPos, level.getGameTime() + newPenalty);
    }

    /**
     * 标记单个目标传输成功并清除其休眠退避
     */
    public void markDestinationSuccessful(Direction side, BlockPos destinationPos, int pipeIndex) {
        if (pipeIndex < 0 || pipeIndex > 5) {
            return;
        }
        if (destinationPenalties != null) {
            destinationPenalties[pipeIndex].removeInt(destinationPos);
        }
        if (destinationSleepTimes != null) {
            destinationSleepTimes[pipeIndex].removeLong(destinationPos);
        }
    }

    /**
     * 上次轮询到的槽位索引
     */
    public int getLastSlot(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        return index < 0 ? 0 : lastSlot[index];
    }

    public void setLastSlot(Direction side, int pipeIndex, int slot) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        lastSlot[index] = slot;
    }

    private int getMaxSleepTicks(int pipeIndex) {
        if (pipeIndex < 0 || pipeIndex >= types.length) {
            return 40;
        }
        int maxSleep = types[pipeIndex].getMaxSleepTicks();
        return maxSleep <= 0 ? 0 : maxSleep;
    }

    @SuppressWarnings("unchecked")
    private Object2LongOpenHashMap<BlockPos>[] destinationSleepMaps() {
        if (destinationSleepTimes == null) {
            Object2LongOpenHashMap<BlockPos>[] maps = new Object2LongOpenHashMap[6];
            for (int i = 0; i < 6; i++) {
                maps[i] = new Object2LongOpenHashMap<>();
            }
            destinationSleepTimes = maps;
        }
        return destinationSleepTimes;
    }

    @SuppressWarnings("unchecked")
    private Object2IntOpenHashMap<BlockPos>[] destinationPenaltyMaps() {
        if (destinationPenalties == null) {
            Object2IntOpenHashMap<BlockPos>[] maps = new Object2IntOpenHashMap[6];
            for (int i = 0; i < 6; i++) {
                maps[i] = new Object2IntOpenHashMap<>();
            }
            destinationPenalties = maps;
        }
        return destinationPenalties;
    }

    @Override
    protected void clearSleepCaches() {
        if (destinationSleepTimes != null) {
            for (Object2LongOpenHashMap<BlockPos> map : destinationSleepTimes) {
                map.clear();
            }
        }
        if (destinationPenalties != null) {
            for (Object2IntOpenHashMap<BlockPos> map : destinationPenalties) {
                map.clear();
            }
        }
    }

    @Override
    protected int getDefaultFrequency(Direction side, int pipeIndex) {
        if (pipeIndex < 0 || pipeIndex >= types.length) {
            return 1;
        }
        return types[pipeIndex].getDefaultFrequency(getUpgrade(side));
    }

    @Override
    protected boolean isCustomFrequencyEnabled(Direction side, int pipeIndex) {
        if (pipeIndex < 0 || pipeIndex >= types.length) {
            return false;
        }
        return types[pipeIndex].isCustomFrequencyEnabled();
    }

    @Override
    public void tick() {
        super.tick();

        if (level.isClientSide) {
            return;
        }

        for (PipeType<?, ?> type : getPipeTypes()) {
            type.tick(this);
        }

        if (hasType(EnergyPipeType.INSTANCE)) {
            for (Direction side : Direction.values()) {
                if (isExtracting(side)) {
                    if (energyStorages[side.get3DDataValue()] != null) {
                        energyStorages[side.get3DDataValue()].tick();
                    }
                }
            }
        }
    }

    public void invalidateCapabilities() {
        if (level != null) {
            level.invalidateCapabilities(worldPosition);
        }
        if (!hasType(EnergyPipeType.INSTANCE)) {
            return;
        }
        for (Direction dir : Direction.values()) {
            if (!isExtracting(dir)) {
                //TODO Check if this causes issues when reloading or other edge cases
                energyStorages[dir.get3DDataValue()] = null;
            }
        }
    }

    @Override
    public void setExtracting(Direction side, boolean extracting) {
        super.setExtracting(side, extracting);
        invalidateCapabilities();
        if (!extracting) {
            clearSleepCaches();
        }
    }

    @Override
    public void invalidateAllCaches() {
        super.invalidateAllCaches();
        for (Direction side : Direction.values()) {
            for (int i = 0; i < types.length; i++) {
                recalculateEffectiveFrequency(side, i);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag compound, HolderLookup.Provider provider) {
        super.loadAdditional(compound, provider);
        invalidateCapabilities();
    }

    @Override
    public void setRemoved() {
        invalidateCapabilities();
        super.setRemoved();
    }

    @Override
    public boolean canInsert(Level level, Connection connection) {
        for (PipeType<?, ?> type : types) {
            for (BlockCapability<?, Direction> provider : type.getCapabilities()) {
                if (connection.getCapability(provider) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean pushRecursion() {
        if (recursionDepth >= 1) {
            return true;
        }
        recursionDepth++;
        return false;
    }

    public void popRecursion() {
        recursionDepth--;
    }

}
