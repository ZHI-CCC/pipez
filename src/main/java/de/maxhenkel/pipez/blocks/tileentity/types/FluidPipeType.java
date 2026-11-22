package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.pipez.*;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.datacomponents.FluidData;
import de.maxhenkel.pipez.items.ModItems;
import de.maxhenkel.pipez.utils.FilterMatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class FluidPipeType extends PipeType<Fluid, FluidData> {

    public static final FluidPipeType INSTANCE = new FluidPipeType();

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return Capabilities.FluidHandler.BLOCK;
    }

    @Nullable
    @Override
    public Filter<?, Fluid> createFilter() {
        return new FluidFilter();
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.fluid";
    }

    @Override
    public String getRateTranslationKey() {
        return "tooltip.pipez.transfer_rate.fluid";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.FLUID_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.fluid", getRate(upgrade));
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
            IFluidHandler fluidHandler = extractingConnection.getFluidHandler();
            if (fluidHandler == null) {
                continue;
            }

            List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

            if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
                insertEqually(tileEntity, side, connections, fluidHandler);
            } else {
                insertOrdered(tileEntity, side, connections, fluidHandler);
            }
        }
    }

    protected void insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IFluidHandler fluidHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        int targetCount = connections.size();
        if (targetCount == 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int mbToTransfer = getRate(tileEntity, side);
        if (mbToTransfer <= 0) {
            return;
        }
        int initialTransfer = mbToTransfer;
        int totalTanks = fluidHandler.getTanks();
        if (totalTanks <= 0) {
            return;
        }
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean isBlacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        boolean isCorrectionEnabled = tileEntity.isTransferCorrectionEnabled(side, pipeIndex);
        boolean[] connectionsFull = new boolean[targetCount];
        IFluidHandler[] validHandlers = new IFluidHandler[targetCount];
        PipeTileEntity.Connection[] validConnections = new PipeTileEntity.Connection[targetCount];
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        int startTank = tileEntity.getLastSlot(side, pipeIndex) % totalTanks;
        boolean keepTrying = true;
        int loopCount = 0;
        while (keepTrying && mbToTransfer > 0 && ++loopCount <= 1000) {
            boolean madeProgressThisPass = false;
            int scanned = 0;
            while (scanned < totalTanks && mbToTransfer > 0) {
                int currentTank = (startTank + scanned) % totalTanks;
                FluidStack simulatedExtract = simulateDrain(fluidHandler, currentTank, mbToTransfer);
                if (simulatedExtract.isEmpty()) {
                    scanned++;
                    continue;
                }
                int validTargets = 0;
                for (int j = 0; j < targetCount; j++) {
                    if (connectionsFull[j]) {
                        continue;
                    }
                    PipeTileEntity.Connection connection = connections.get(j);
                    if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                        continue;
                    }
                    if (FilterMatcher.canInsertFluid(this, provider, connection, simulatedExtract, filters) == isBlacklist) {
                        connectionsFull[j] = true;
                        continue;
                    }
                    IFluidHandler destination = connection.getFluidHandler();
                    if (destination == null) {
                        continue;
                    }
                    validHandlers[validTargets] = destination;
                    validConnections[validTargets] = connection;
                    validTargets++;
                }
                if (validTargets == 0) {
                    scanned++;
                    continue;
                }
                int totalAmount = simulatedExtract.getAmount();
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
                    FluidStack insertStack = amountToSend == totalAmount ? simulatedExtract : simulatedExtract.copyWithAmount(amountToSend);
                    int canInsert = validHandlers[targetIndex].fill(insertStack, IFluidHandler.FluidAction.SIMULATE);
                    if (canInsert > 0) {
                        FluidStack actuallyExtracted = fluidHandler.drain(simulatedExtract.copyWithAmount(canInsert), IFluidHandler.FluidAction.EXECUTE);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        validHandlers[targetIndex].fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                        successfullyExtracted += actuallyExtracted.getAmount();
                        tileEntity.markDestinationSuccessful(side, validConnections[targetIndex].getPos(), pipeIndex);
                    } else {
                        tileEntity.markDestinationPenalty(side, validConnections[targetIndex].getPos(), pipeIndex);
                        for (int k = 0; k < targetCount; k++) {
                            if (connections.get(k) == validConnections[targetIndex]) {
                                connectionsFull[k] = true;
                                break;
                            }
                        }
                    }
                }
                tileEntity.setRoundRobinIndex(side, this, (startIndex + remainder) % validTargets);
                if (successfullyExtracted > 0) {
                    mbToTransfer -= successfullyExtracted;
                    madeProgressThisPass = true;
                    tileEntity.setLastSlot(side, pipeIndex, currentTank);
                }
                scanned++;
            }
            if (madeProgressThisPass && isCorrectionEnabled) {
                continue;
            }
            keepTrying = false;
        }
        if (mbToTransfer < initialTransfer) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    protected void insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IFluidHandler fluidHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        if (connections.isEmpty()) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int mbToTransfer = getRate(tileEntity, side);
        if (mbToTransfer <= 0) {
            return;
        }
        int initialTransfer = mbToTransfer;
        int totalTanks = fluidHandler.getTanks();
        if (totalTanks <= 0) {
            return;
        }
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
            IFluidHandler destination = connection.getFluidHandler();
            if (destination == null) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
                continue;
            }
            boolean hasInsertedIntoDest = false;
            boolean destRejected = false;
            boolean keepTrying = true;
            int loopCount = 0;
            while (keepTrying && mbToTransfer > 0 && ++loopCount <= 1000) {
                boolean madeProgressThisPass = false;
                for (int i = 0; i < totalTanks; i++) {
                    if (mbToTransfer <= 0) {
                        break connectionLoop;
                    }
                    FluidStack simulatedExtract = simulateDrain(fluidHandler, i, mbToTransfer);
                    if (simulatedExtract.isEmpty()) {
                        continue;
                    }
                    if (FilterMatcher.canInsertFluid(this, provider, connection, simulatedExtract, filters) == isBlacklist) {
                        destRejected = true;
                        continue;
                    }
                    int canInsert = destination.fill(simulatedExtract, IFluidHandler.FluidAction.SIMULATE);
                    if (canInsert > 0) {
                        FluidStack actuallyExtracted = fluidHandler.drain(simulatedExtract.copyWithAmount(canInsert), IFluidHandler.FluidAction.EXECUTE);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        destination.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                        mbToTransfer -= actuallyExtracted.getAmount();
                        hasInsertedIntoDest = true;
                        madeProgressThisPass = true;
                        tileEntity.markDestinationSuccessful(side, connection.getPos(), pipeIndex);
                    } else {
                        destRejected = true;
                    }
                }
                if (madeProgressThisPass && isCorrectionEnabled) {
                    continue;
                }
                keepTrying = false;
            }
            if (!hasInsertedIntoDest && destRejected) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
            }
        }
        if (mbToTransfer < initialTransfer) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    /**
     * 模拟抽取指定储罐，空罐时按任意流体回退
     */
    private FluidStack simulateDrain(IFluidHandler fluidHandler, int tank, int amount) {
        FluidStack fluidInTank = fluidHandler.getFluidInTank(tank);
        if (!fluidInTank.isEmpty()) {
            FluidStack copy = fluidInTank.copy();
            copy.setAmount(amount);
            FluidStack simulated = fluidHandler.drain(copy, IFluidHandler.FluidAction.SIMULATE);
            if (!simulated.isEmpty()) {
                return simulated;
            }
        }
        if (tank == 0) {
            return fluidHandler.drain(amount, IFluidHandler.FluidAction.SIMULATE);
        }
        return FluidStack.EMPTY;
    }

    @Override
    public boolean isCustomFrequencyEnabled() {
        return Main.SERVER_CONFIG == null || Main.SERVER_CONFIG.enableFluidCustomFrequency.get();
    }

    @Override
    public int getMaxSleepTicks() {
        if (Main.SERVER_CONFIG == null) {
            return 40;
        }
        return Main.SERVER_CONFIG.fluidMaxSleepTicks.get();
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.fluidPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.fluidPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.fluidPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.fluidPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.fluidPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<FluidData> getDataComponentType() {
        return ModItems.FLUID_DATA_COMPONENT.get();
    }

    private static final FluidData DEFAULT = new FluidData(UpgradeTileEntity.FilterMode.WHITELIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.ROUND_ROBIN, Collections.emptyList());

    @Override
    public FluidData defaultData() {
        return DEFAULT;
    }

}
