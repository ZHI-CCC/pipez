package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.corelib.item.ItemUtils;
import de.maxhenkel.pipez.*;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.datacomponents.ItemData;
import de.maxhenkel.pipez.items.ModItems;
import de.maxhenkel.pipez.utils.FilterMatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ItemPipeType extends PipeType<Item, ItemData> {

    public static final ItemPipeType INSTANCE = new ItemPipeType();

    /**
     * 可复用的目标处理器缓冲
     */
    private static final ThreadLocal<IItemHandler[]> HANDLER_BUFFER = ThreadLocal.withInitial(() -> new IItemHandler[128]);
    /**
     * 可复用的目标连接缓冲
     */
    private static final ThreadLocal<PipeTileEntity.Connection[]> CONNECTION_BUFFER = ThreadLocal.withInitial(() -> new PipeTileEntity.Connection[128]);
    /**
     * 可复用的目标掩码缓冲
     */
    private static final ThreadLocal<boolean[]> MASK_BUFFER = ThreadLocal.withInitial(() -> new boolean[128]);

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return Capabilities.ItemHandler.BLOCK;
    }

    @Override
    public Filter<?, Item> createFilter() {
        return new ItemFilter();
    }

    @Override
    public UpgradeTileEntity.Distribution getDefaultDistribution() {
        return UpgradeTileEntity.Distribution.NEAREST;
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.item";
    }

    @Override
    public String getRateTranslationKey() {
        return "tooltip.pipez.transfer_rate.item";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.ITEM_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.item", getRate(upgrade), getSpeed(upgrade));
    }

    @Override
    public void tick(PipeLogicTileEntity tileEntity) {
        for (Direction side : Direction.values()) {
            if (!tileEntity.isExtracting(side)) {
                continue;
            }
            if (!tileEntity.shouldWork(side, this)) {
                continue;
            }
            PipeTileEntity.Connection extractingConnection = tileEntity.getExtractingConnection(side);
            if (extractingConnection == null) {
                continue;
            }
            IItemHandler itemHandler = extractingConnection.getItemHandler();
            if (itemHandler == null) {
                continue;
            }

            List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

            if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
                insertEqually(tileEntity, side, connections, itemHandler);
            } else {
                insertOrdered(tileEntity, side, connections, itemHandler);
            }
        }
    }

    protected void insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IItemHandler itemHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        int targetCount = connections.size();
        if (targetCount == 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int itemsToTransfer = getRate(tileEntity, side);
        if (itemsToTransfer <= 0) {
            return;
        }
        int initialTransfer = itemsToTransfer;
        int totalSlots = itemHandler.getSlots();
        if (totalSlots <= 0) {
            return;
        }
        boolean[] fallbackMask = MASK_BUFFER.get();
        if (fallbackMask.length < targetCount) {
            fallbackMask = new boolean[targetCount];
            MASK_BUFFER.set(fallbackMask);
        }
        Arrays.fill(fallbackMask, 0, targetCount, false);
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        int validDestCount = 0;
        for (int i = 0; i < targetCount; i++) {
            PipeTileEntity.Connection connection = connections.get(i);
            if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                fallbackMask[i] = true;
                continue;
            }
            IItemHandler destination = connection.getItemHandler();
            if (destination == null || isFull(destination)) {
                fallbackMask[i] = true;
                continue;
            }
            validDestCount++;
        }
        if (validDestCount == 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean isBlacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        boolean isCorrectionEnabled = tileEntity.isTransferCorrectionEnabled(side, pipeIndex);
        IItemHandler[] validHandlers = HANDLER_BUFFER.get();
        if (validHandlers.length < targetCount) {
            validHandlers = new IItemHandler[targetCount];
            HANDLER_BUFFER.set(validHandlers);
        }
        PipeTileEntity.Connection[] validConnections = CONNECTION_BUFFER.get();
        if (validConnections.length < targetCount) {
            validConnections = new PipeTileEntity.Connection[targetCount];
            CONNECTION_BUFFER.set(validConnections);
        }
        int startSlot = tileEntity.getLastSlot(side, pipeIndex) % totalSlots;
        boolean keepTrying = true;
        int loopCount = 0;
        while (keepTrying && itemsToTransfer > 0 && ++loopCount <= 1000) {
            boolean madeProgressThisPass = false;
            int scanned = 0;
            while (scanned < totalSlots && itemsToTransfer > 0) {
                int currentSlot = (startSlot + scanned) % totalSlots;
                ItemStack simulatedExtract = itemHandler.extractItem(currentSlot, itemsToTransfer, true);
                if (simulatedExtract.isEmpty()) {
                    scanned++;
                    continue;
                }
                int validTargets = 0;
                for (int j = 0; j < targetCount; j++) {
                    if (fallbackMask[j]) {
                        continue;
                    }
                    PipeTileEntity.Connection connection = connections.get(j);
                    if (FilterMatcher.canInsertItem(this, provider, connection, simulatedExtract, filters) == isBlacklist) {
                        continue;
                    }
                    validHandlers[validTargets] = connection.getItemHandler();
                    validConnections[validTargets] = connection;
                    validTargets++;
                }
                if (validTargets == 0) {
                    scanned++;
                    continue;
                }
                int totalAmount = simulatedExtract.getCount();
                int baseAmount = totalAmount / validTargets;
                int remainder = totalAmount % validTargets;
                int successfullyExtracted = 0;
                int p = tileEntity.getRoundRobinIndex(side, this);
                int startIndex = p % validTargets;
                for (int j = 0; j < validTargets; j++) {
                    int targetIndex = (startIndex + j) % validTargets;
                    int amountToSend = baseAmount + (j < remainder ? 1 : 0);
                    if (amountToSend <= 0) {
                        continue;
                    }
                    ItemStack insertStack = amountToSend == totalAmount ? simulatedExtract : simulatedExtract.copyWithCount(amountToSend);
                    ItemStack notInsertedSim = ItemHandlerHelper.insertItem(validHandlers[targetIndex], insertStack, true);
                    int canInsert = amountToSend - notInsertedSim.getCount();
                    if (canInsert > 0) {
                        ItemStack actuallyExtracted = itemHandler.extractItem(currentSlot, canInsert, false);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        ItemHandlerHelper.insertItem(validHandlers[targetIndex], actuallyExtracted, false);
                        successfullyExtracted += actuallyExtracted.getCount();
                        tileEntity.markDestinationSuccessful(side, validConnections[targetIndex].getPos(), pipeIndex);
                    } else {
                        tileEntity.markDestinationPenalty(side, validConnections[targetIndex].getPos(), pipeIndex);
                    }
                }
                tileEntity.setRoundRobinIndex(side, this, (startIndex + remainder) % validTargets);
                if (successfullyExtracted > 0) {
                    itemsToTransfer -= successfullyExtracted;
                    madeProgressThisPass = true;
                    tileEntity.setLastSlot(side, pipeIndex, currentSlot);
                }
                scanned++;
            }
            if (madeProgressThisPass && isCorrectionEnabled) {
                continue;
            }
            keepTrying = false;
        }
        if (itemsToTransfer < initialTransfer) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    protected void insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IItemHandler itemHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        if (connections.isEmpty()) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int itemsToTransfer = getRate(tileEntity, side);
        if (itemsToTransfer <= 0) {
            return;
        }
        int initialTransfer = itemsToTransfer;
        int totalSlots = itemHandler.getSlots();
        if (totalSlots <= 0) {
            return;
        }
        ArrayList<ItemStack> nonFittingItems = new ArrayList<>();
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean isBlacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        boolean isCorrectionEnabled = tileEntity.isTransferCorrectionEnabled(side, pipeIndex);
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        connectionLoop:
        for (PipeTileEntity.Connection connection : connections) {
            if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                continue;
            }
            nonFittingItems.clear();
            IItemHandler destination = connection.getItemHandler();
            if (destination == null || isFull(destination)) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
                continue;
            }
            boolean hasInsertedIntoDest = false;
            boolean keepTrying = true;
            int loopCount = 0;
            while (keepTrying && itemsToTransfer > 0 && ++loopCount <= 1000) {
                boolean madeProgressThisPass = false;
                for (int i = 0; i < totalSlots; i++) {
                    if (itemsToTransfer <= 0) {
                        break connectionLoop;
                    }
                    ItemStack simulatedExtract = itemHandler.extractItem(i, itemsToTransfer, true);
                    if (simulatedExtract.isEmpty()) {
                        continue;
                    }
                    boolean nonFitting = false;
                    for (ItemStack stack : nonFittingItems) {
                        if (ItemUtils.isStackable(stack, simulatedExtract)) {
                            nonFitting = true;
                            break;
                        }
                    }
                    if (nonFitting || FilterMatcher.canInsertItem(this, provider, connection, simulatedExtract, filters) == isBlacklist) {
                        continue;
                    }
                    ItemStack notInsertedSim = ItemHandlerHelper.insertItem(destination, simulatedExtract, true);
                    int canInsert = simulatedExtract.getCount() - notInsertedSim.getCount();
                    if (canInsert > 0) {
                        ItemStack actuallyExtracted = itemHandler.extractItem(i, canInsert, false);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        ItemHandlerHelper.insertItem(destination, actuallyExtracted, false);
                        itemsToTransfer -= actuallyExtracted.getCount();
                        hasInsertedIntoDest = true;
                        madeProgressThisPass = true;
                        tileEntity.markDestinationSuccessful(side, connection.getPos(), pipeIndex);
                    } else {
                        nonFittingItems.add(simulatedExtract);
                    }
                }
                if (madeProgressThisPass && isCorrectionEnabled) {
                    continue;
                }
                keepTrying = false;
            }
            if (!hasInsertedIntoDest) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
            }
        }
        if (itemsToTransfer < initialTransfer) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    private boolean isFull(IItemHandler itemHandler) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stackInSlot = itemHandler.getStackInSlot(i);
            if (stackInSlot.getCount() < itemHandler.getSlotLimit(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getDefaultFrequency(@Nullable Upgrade upgrade) {
        return getSpeed(upgrade);
    }

    @Override
    public boolean isCustomFrequencyEnabled() {
        return Main.SERVER_CONFIG == null || Main.SERVER_CONFIG.enableItemCustomFrequency.get();
    }

    @Override
    public int getMaxSleepTicks() {
        if (Main.SERVER_CONFIG == null) {
            return 40;
        }
        return Main.SERVER_CONFIG.itemMaxSleepTicks.get();
    }

    public int getSpeed(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.itemPipeSpeed.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.itemPipeSpeedBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.itemPipeSpeedImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.itemPipeSpeedAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.itemPipeSpeedUltimate.get();
            case INFINITY:
            default:
                return 1;
        }
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.itemPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.itemPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.itemPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.itemPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.itemPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<ItemData> getDataComponentType() {
        return ModItems.ITEM_DATA_COMPONENT.get();
    }

    private static final ItemData DEFAULT = new ItemData(UpgradeTileEntity.FilterMode.WHITELIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.NEAREST, Collections.emptyList());

    @Override
    public ItemData defaultData() {
        return DEFAULT;
    }

}
