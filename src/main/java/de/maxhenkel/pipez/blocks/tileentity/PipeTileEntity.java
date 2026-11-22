package de.maxhenkel.pipez.blocks.tileentity;

import de.maxhenkel.corelib.blockentity.ITickableBlockEntity;
import de.maxhenkel.pipez.DirectionalPosition;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.capabilities.ModCapabilities;
import de.maxhenkel.pipez.utils.MekanismUtils;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.*;

public abstract class PipeTileEntity extends BlockEntity implements ITickableBlockEntity {

    /**
     * 自定义抽取频率的上限（tick）
     */
    public static final int MAX_CUSTOM_FREQUENCY = 200;
    /**
     * 每个方向最多六种管道类型的频率槽位总数
     */
    public static final int FREQUENCY_SLOTS = Direction.values().length * 6;

    @Nullable
    protected List<Connection> connectionCache;
    @Nullable
    protected Connection[] extractingConnectionCache;
    protected boolean[] extractingSides;
    protected boolean[] disconnectedSides;

    protected final int[] customFrequencies;
    protected final int[] effectiveFrequencies;
    protected final boolean[] transferCorrections;
    protected final boolean[] smartSleepDisabled;

    /**
     * Invalidating the cache five ticks after load, because Mekanism is broken!
     */
    private int invalidateCountdown;

    public PipeTileEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
        super(tileEntityTypeIn, pos, state);
        extractingSides = new boolean[Direction.values().length];
        disconnectedSides = new boolean[Direction.values().length];
        customFrequencies = new int[FREQUENCY_SLOTS];
        effectiveFrequencies = new int[FREQUENCY_SLOTS];
        transferCorrections = new boolean[FREQUENCY_SLOTS];
        smartSleepDisabled = new boolean[FREQUENCY_SLOTS];
    }

    public List<Connection> getConnections() {
        if (level == null) {
            return new ArrayList<>();
        }
        if (connectionCache == null) {
            updateConnectionCache();
            if (connectionCache == null) {
                return new ArrayList<>();
            }
        }
        return connectionCache;
    }

    @Nullable
    public Connection getExtractingConnection(Direction side) {
        if (level == null) {
            return null;
        }
        if (extractingConnectionCache == null) {
            updateExtractingConnectionCache();
            if (extractingConnectionCache == null) {
                return null;
            }
        }
        return extractingConnectionCache[side.get3DDataValue()];
    }

    public static void markPipesDirty(Level world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        if (!(block instanceof PipeBlock pipeBlock)) {
            return;
        }

        PipeTileEntity pipeTe = pipeBlock.getTileEntity(world, pos);
        if (pipeTe != null) {
            for (Direction side : Direction.values()) {
                BlockPos neighborPos = pos.relative(side);
                if (!pipeTe.isExtracting(side) || !world.isLoaded(neighborPos) || pipeBlock.canConnectTo(world, pos, side) || world.getBlockEntity(neighborPos) != null) {
                    continue;
                }
                pipeTe.setExtracting(side, false);
                if (!pipeTe.hasReasonToStay()) {
                    pipeBlock.setHasData(world, pos, false);
                }
                pipeTe.syncData();
            }
        }

        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> travelPositions = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(pos);
        travelPositions.add(pos);
        queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos currentPos = queue.pollFirst();
            Block currentBlock = world.getBlockState(currentPos).getBlock();
            if (!(currentBlock instanceof PipeBlock currentPipeBlock)) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                if (!currentPipeBlock.isConnected(world, currentPos, direction)) {
                    continue;
                }
                BlockPos p = currentPos.relative(direction);
                if (visited.add(p)) {
                    travelPositions.add(p);
                    queue.add(p);
                }
            }
        }
        for (BlockPos p : travelPositions) {
            BlockEntity te = world.getBlockEntity(p);
            if (!(te instanceof PipeTileEntity pipe)) {
                continue;
            }
            pipe.connectionCache = null;
        }
    }

    private void updateConnectionCache() {
        clearSleepCaches();
        if (!(level instanceof ServerLevel serverLevel)) {
            connectionCache = null;
            return;
        }
        BlockState blockState = getBlockState();
        if (!(blockState.getBlock() instanceof PipeBlock) || !isExtracting()) {
            connectionCache = null;
            return;
        }

        Map<DirectionalPosition, Connection> connections = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> distances = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos startPos = getBlockPos();
        queue.add(startPos);
        distances.put(startPos, 1);
        visited.add(startPos);
        while (!queue.isEmpty()) {
            BlockPos current = queue.pollFirst();
            int distance = distances.get(current);
            Block block = serverLevel.getBlockState(current).getBlock();
            if (!(block instanceof PipeBlock pipeBlock)) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                if (!pipeBlock.isConnected(serverLevel, current, direction)) {
                    continue;
                }
                BlockPos p = current.relative(direction);
                DirectionalPosition dp = new DirectionalPosition(p, direction.getOpposite());
                Connection connection = new Connection(serverLevel, dp.getPos(), dp.getDirection(), distance);
                if (!isExtracting(level, current, direction) && canInsert(level, connection)) {
                    Connection existing = connections.get(dp);
                    if (existing == null || existing.getDistance() > distance) {
                        connections.put(dp, connection);
                    }
                } else if (visited.add(p)) {
                    queue.add(p);
                    distances.put(p, distance + 1);
                }
            }
        }

        connectionCache = new ArrayList<>(connections.values());
    }

    private void updateExtractingConnectionCache() {
        if (!(level instanceof ServerLevel serverLevel)) {
            connectionCache = null;
            return;
        }
        BlockState blockState = getBlockState();
        if (!(blockState.getBlock() instanceof PipeBlock)) {
            extractingConnectionCache = null;
            return;
        }

        extractingConnectionCache = new Connection[Direction.values().length];

        for (Direction direction : Direction.values()) {
            if (!isExtracting(direction)) {
                extractingConnectionCache[direction.get3DDataValue()] = null;
                continue;
            }
            extractingConnectionCache[direction.get3DDataValue()] = new Connection(serverLevel, getBlockPos().relative(direction), direction.getOpposite(), 1);
        }
    }

    private boolean isExtracting(Level level, BlockPos pos, Direction direction) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te instanceof PipeTileEntity pipe) {
            if (pipe.isExtracting(direction)) {
                return true;
            }
        }
        return false;
    }

    public abstract boolean canInsert(Level level, Connection connection);

    @Override
    public void tick() {
        if (invalidateCountdown >= 0) {
            invalidateCountdown--;
            if (invalidateCountdown <= 0) {
                connectionCache = null;
            }
        }
    }

    public boolean isExtracting(Direction side) {
        return extractingSides[side.get3DDataValue()];
    }

    public boolean isExtracting() {
        for (boolean extract : extractingSides) {
            if (extract) {
                return true;
            }
        }
        return false;
    }

    public boolean hasReasonToStay() {
        if (isExtracting()) {
            return true;
        }
        for (boolean disconnected : disconnectedSides) {
            if (disconnected) {
                return true;
            }
        }
        return false;
    }

    public void setExtracting(Direction side, boolean extracting) {
        extractingSides[side.get3DDataValue()] = extracting;
        extractingConnectionCache = null;
        setChanged();
    }

    public boolean isDisconnected(Direction side) {
        return disconnectedSides[side.get3DDataValue()];
    }

    public void setDisconnected(Direction side, boolean disconnected) {
        disconnectedSides[side.get3DDataValue()] = disconnected;
        setChanged();
    }

    /**
     * 计算方向与管道类型索引对应的频率槽位
     */
    protected static int frequencySlot(Direction side, int pipeIndex) {
        if (pipeIndex < 0 || pipeIndex > 5) {
            return -1;
        }
        return side.get3DDataValue() * 6 + pipeIndex;
    }

    /**
     * 当前生效的抽取频率（tick），零值自定义回退到原生频率
     */
    public int getEffectiveFrequency(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        return index < 0 ? 1 : effectiveFrequencies[index];
    }

    /**
     * 设置自定义抽取频率，0 表示使用原生频率
     */
    public void setCustomFrequency(Direction side, int pipeIndex, int frequency) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        customFrequencies[index] = Mth.clamp(frequency, 0, MAX_CUSTOM_FREQUENCY);
        recalculateEffectiveFrequency(side, pipeIndex);
    }

    /**
     * 依据配置与当前升级重算生效频率
     */
    public void recalculateEffectiveFrequency(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        int customFrequency = customFrequencies[index];
        int defaultFrequency = getDefaultFrequency(side, pipeIndex);
        if (isCustomFrequencyEnabled(side, pipeIndex) && customFrequency > 0) {
            if (Main.SERVER_CONFIG != null && Main.SERVER_CONFIG.restrictFrequencyToNative.get() && customFrequency < defaultFrequency) {
                customFrequencies[index] = 0;
                effectiveFrequencies[index] = defaultFrequency;
            } else {
                effectiveFrequencies[index] = customFrequency;
            }
        } else {
            effectiveFrequencies[index] = defaultFrequency;
        }
    }

    /**
     * 该连接面上管道类型的原生抽取频率
     */
    protected int getDefaultFrequency(Direction side, int pipeIndex) {
        return 1;
    }

    /**
     * 该连接面上管道类型是否允许自定义抽取频率
     */
    protected boolean isCustomFrequencyEnabled(Direction side, int pipeIndex) {
        return false;
    }

    /**
     * 是否启用传输修正
     */
    public boolean isTransferCorrectionEnabled(Direction side, int pipeIndex) {
        int index = frequencySlot(side, pipeIndex);
        return index >= 0 && transferCorrections[index];
    }

    public void setTransferCorrectionEnabled(Direction side, int pipeIndex, boolean enabled) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        transferCorrections[index] = enabled;
    }

    /**
     * 是否对该连接面关闭智能休眠
     */
    public boolean isSmartSleepDisabled(Direction side, int pipeIndex) {
        if (Main.SERVER_CONFIG == null || !Main.SERVER_CONFIG.showSmartSleepToggle.get()) {
            return false;
        }
        int index = frequencySlot(side, pipeIndex);
        return index >= 0 && smartSleepDisabled[index];
    }

    public void setSmartSleepDisabled(Direction side, int pipeIndex, boolean disabled) {
        int index = frequencySlot(side, pipeIndex);
        if (index < 0) {
            return;
        }
        smartSleepDisabled[index] = disabled;
    }

    /**
     * 连接缓存重建时同步清理目标休眠缓存
     */
    protected void clearSleepCaches() {

    }

    @Override
    protected void loadAdditional(CompoundTag compound, HolderLookup.Provider provider) {
        super.loadAdditional(compound, provider);
        extractingSides = new boolean[Direction.values().length];
        ListTag extractingList = compound.getList("ExtractingSides", Tag.TAG_BYTE);
        loadSideFlags(extractingList, extractingSides);

        disconnectedSides = new boolean[Direction.values().length];
        ListTag disconnectedList = compound.getList("DisconnectedSides", Tag.TAG_BYTE);
        loadSideFlags(disconnectedList, disconnectedSides);

        Arrays.fill(customFrequencies, 0);
        if (compound.contains("PipezOptCustomFreqs")) {
            int[] loaded = compound.getIntArray("PipezOptCustomFreqs");
            if (loaded.length > 0) {
                System.arraycopy(loaded, 0, customFrequencies, 0, Math.min(loaded.length, customFrequencies.length));
            }
        }
        Arrays.fill(transferCorrections, false);
        if (compound.contains("PipezOptTransferCorrections")) {
            byte[] loaded = compound.getByteArray("PipezOptTransferCorrections");
            int count = Math.min(loaded.length, transferCorrections.length);
            for (int i = 0; i < count; i++) {
                transferCorrections[i] = loaded[i] > 0;
            }
        }
        Arrays.fill(smartSleepDisabled, false);
        if (compound.contains("PipezOptSmartSleepDisabled")) {
            byte[] loaded = compound.getByteArray("PipezOptSmartSleepDisabled");
            int count = Math.min(loaded.length, smartSleepDisabled.length);
            for (int i = 0; i < count; i++) {
                smartSleepDisabled[i] = loaded[i] > 0;
            }
        }
        invalidateCountdown = 10;
    }

    /**
     * 读取方向标志列表，条目不足时按已有条目回填
     */
    private static void loadSideFlags(ListTag list, boolean[] dest) {
        if (list.size() >= dest.length) {
            for (int i = 0; i < dest.length; i++) {
                Tag tag = list.get(i);
                if (tag instanceof ByteTag b) {
                    dest[i] = b.getAsByte() != 0;
                }
            }
        } else {
            int count = Math.min(list.size(), dest.length);
            for (int i = 0; i < count; i++) {
                Tag tag = list.get(i);
                if (tag instanceof ByteTag b) {
                    dest[i] = b.getAsByte() != 0;
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
        super.saveAdditional(compound, provider);

        ListTag extractingList = new ListTag();
        for (boolean extractingSide : extractingSides) {
            extractingList.add(ByteTag.valueOf(extractingSide));
        }
        compound.put("ExtractingSides", extractingList);

        ListTag disconnectedList = new ListTag();
        for (boolean disconnected : disconnectedSides) {
            disconnectedList.add(ByteTag.valueOf(disconnected));
        }
        compound.put("DisconnectedSides", disconnectedList);

        boolean hasCustomFrequency = false;
        for (int frequency : customFrequencies) {
            if (frequency > 0) {
                hasCustomFrequency = true;
                break;
            }
        }
        if (hasCustomFrequency) {
            compound.putIntArray("PipezOptCustomFreqs", customFrequencies);
        }
        boolean hasCorrection = false;
        byte[] corrections = new byte[transferCorrections.length];
        for (int i = 0; i < transferCorrections.length; i++) {
            if (transferCorrections[i]) {
                corrections[i] = 1;
                hasCorrection = true;
            }
        }
        if (hasCorrection) {
            compound.putByteArray("PipezOptTransferCorrections", corrections);
        }
        boolean hasSleepDisabled = false;
        byte[] sleepFlags = new byte[smartSleepDisabled.length];
        for (int i = 0; i < smartSleepDisabled.length; i++) {
            if (smartSleepDisabled[i]) {
                sleepFlags[i] = 1;
                hasSleepDisabled = true;
            }
        }
        if (hasSleepDisabled) {
            compound.putByteArray("PipezOptSmartSleepDisabled", sleepFlags);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void syncData(ServerPlayer player) {
        player.connection.send(getUpdatePacket());
    }

    public void syncData() {
        if (level == null || level.isClientSide) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(getBlockPos());
        ((ServerChunkCache) level.getChunkSource()).chunkMap.getPlayers(chunk.getPos(), false).forEach(e -> e.connection.send(getUpdatePacket()));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag updateTag = super.getUpdateTag(provider);
        saveAdditional(updateTag, provider);
        return updateTag;
    }

    public static class Connection {
        private final BlockPos pos;
        private final Direction direction;
        private final int distance;
        private BlockCapabilityCache<IItemHandler, Direction> itemHandler;
        private BlockCapabilityCache<IEnergyStorage, Direction> energyHandler;
        private BlockCapabilityCache<IFluidHandler, Direction> fluidHandler;

        private Optional<BlockCapabilityCache<IChemicalHandler, Direction>> chemicalHandler;

        public Connection(ServerLevel level, BlockPos pos, Direction direction, int distance) {
            this.pos = pos;
            this.direction = direction;
            this.distance = distance;

            itemHandler = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos, direction);
            energyHandler = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, pos, direction);
            fluidHandler = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, pos, direction);
            if (MekanismUtils.isMekanismInstalled()) {
                chemicalHandler = Optional.of(BlockCapabilityCache.create(ModCapabilities.CHEMICAL_HANDLER_CAPABILITY, level, pos, direction));
            } else {
                chemicalHandler = Optional.empty();
            }
        }

        public BlockPos getPos() {
            return pos;
        }

        public Direction getDirection() {
            return direction;
        }

        public int getDistance() {
            return distance;
        }

        @Override
        public String toString() {
            return "Connection{" +
                    "pos=" + pos +
                    ", direction=" + direction +
                    ", distance=" + distance +
                    '}';
        }

        @Nullable
        public IItemHandler getItemHandler() {
            return itemHandler.getCapability();
        }

        @Nullable
        public IEnergyStorage getEnergyHandler() {
            return energyHandler.getCapability();
        }

        @Nullable
        public IFluidHandler getFluidHandler() {
            return fluidHandler.getCapability();
        }

        @Nullable
        public IChemicalHandler getChemicalHandler() {
            return chemicalHandler.map(BlockCapabilityCache::getCapability).orElse(null);
        }

        @Nullable
        public <T> T getCapability(BlockCapability<T, Direction> capability) {
            if (capability == Capabilities.ItemHandler.BLOCK) {
                return (T) getItemHandler();
            } else if (capability == Capabilities.EnergyStorage.BLOCK) {
                return (T) getEnergyHandler();
            } else if (capability == Capabilities.FluidHandler.BLOCK) {
                return (T) getFluidHandler();
            }
            if (!MekanismUtils.isMekanismInstalled()) {
                return null;
            }
            if (capability == ModCapabilities.CHEMICAL_HANDLER_CAPABILITY) {
                return (T) getChemicalHandler();
            }
            return null;
        }
    }

}
