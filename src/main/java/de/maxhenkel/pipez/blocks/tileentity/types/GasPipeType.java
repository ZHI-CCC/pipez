package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.pipez.Filter;
import de.maxhenkel.pipez.GasFilter;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.Upgrade;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.capabilities.ModCapabilities;
import de.maxhenkel.pipez.datacomponents.GasData;
import de.maxhenkel.pipez.items.ModItems;
import de.maxhenkel.pipez.utils.FilterMatcher;
import de.maxhenkel.pipez.utils.MekanismUtils;
import mekanism.api.Action;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class GasPipeType extends PipeType<Chemical, GasData> {

    public static final GasPipeType INSTANCE = new GasPipeType();

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return ModCapabilities.CHEMICAL_HANDLER_CAPABILITY;
    }

    @Override
    public Filter<?, Chemical> createFilter() {
        return new GasFilter();
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.gas";
    }

    @Override
    public String getRateTranslationKey() {
        return "tooltip.pipez.transfer_rate.gas";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.GAS_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.gas", getRate(upgrade));
    }

    @Override
    public void tick(PipeLogicTileEntity tileEntity) {
        if (!MekanismUtils.isMekanismInstalled()) {
            return;
        }
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
            IChemicalHandler chemicalHandler = extractingConnection.getChemicalHandler();
            if (chemicalHandler == null) {
                continue;
            }
            tickHandler(tileEntity, chemicalHandler, side);
        }
    }

    private void tickHandler(PipeLogicTileEntity tileEntity, IChemicalHandler gasHandler, Direction side) {
        List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);
        if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
            insertEqually(tileEntity, side, connections, gasHandler);
        } else {
            insertOrdered(tileEntity, side, connections, gasHandler);
        }
    }

    protected void insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IChemicalHandler gasHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        int targetCount = connections.size();
        if (targetCount == 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        long completeAmount = getRate(tileEntity, side);
        long mbToTransfer = completeAmount;
        if (mbToTransfer <= 0L) {
            return;
        }
        int totalTanks = gasHandler.getChemicalTanks();
        if (totalTanks <= 0) {
            return;
        }
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean isBlacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        boolean isCorrectionEnabled = tileEntity.isTransferCorrectionEnabled(side, pipeIndex);
        boolean[] connectionsFull = new boolean[targetCount];
        IChemicalHandler[] validHandlers = new IChemicalHandler[targetCount];
        PipeTileEntity.Connection[] validConnections = new PipeTileEntity.Connection[targetCount];
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        int startTank = tileEntity.getLastSlot(side, pipeIndex) % totalTanks;
        boolean keepTrying = true;
        int loopCount = 0;
        while (keepTrying && mbToTransfer > 0L && ++loopCount <= 1000) {
            boolean madeProgressThisPass = false;
            int scanned = 0;
            while (scanned < totalTanks && mbToTransfer > 0L) {
                int currentTank = (startTank + scanned) % totalTanks;
                ChemicalStack simulatedExtract = simulateExtract(gasHandler, currentTank, mbToTransfer);
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
                    if (FilterMatcher.canInsertGas(this, connection, simulatedExtract, filters) == isBlacklist) {
                        connectionsFull[j] = true;
                        continue;
                    }
                    IChemicalHandler destination = connection.getChemicalHandler();
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
                long totalAmount = simulatedExtract.getAmount();
                long baseAmount = totalAmount / validTargets;
                long remainder = totalAmount % validTargets;
                long successfullyExtracted = 0L;
                int p = tileEntity.getRoundRobinIndex(side, this);
                int startIndex = p % validTargets;
                for (int j = 0; j < validTargets; j++) {
                    int targetIndex = (startIndex + j) % validTargets;
                    long amountToSend = baseAmount + (j < remainder ? 1 : 0);
                    if (amountToSend <= 0L) {
                        continue;
                    }
                    ChemicalStack insertStack = amountToSend == totalAmount ? simulatedExtract : new ChemicalStack(simulatedExtract.getChemical(), amountToSend);
                    ChemicalStack leftOverSim = validHandlers[targetIndex].insertChemical(insertStack, Action.SIMULATE);
                    long canInsert = amountToSend - leftOverSim.getAmount();
                    if (canInsert > 0L) {
                        ChemicalStack actuallyExtracted = gasHandler.extractChemical(new ChemicalStack(simulatedExtract.getChemical(), canInsert), Action.EXECUTE);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        validHandlers[targetIndex].insertChemical(actuallyExtracted, Action.EXECUTE);
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
                tileEntity.setRoundRobinIndex(side, this, (startIndex + (int) remainder) % validTargets);
                if (successfullyExtracted > 0L) {
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
        if (mbToTransfer < completeAmount) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    protected void insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IChemicalHandler gasHandler) {
        int pipeIndex = tileEntity.getIndex(this);
        if (connections.isEmpty()) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        long mbToTransfer = getRate(tileEntity, side);
        if (mbToTransfer <= 0L) {
            return;
        }
        long initialTransfer = mbToTransfer;
        int totalTanks = gasHandler.getChemicalTanks();
        if (totalTanks <= 0) {
            return;
        }
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean isBlacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        boolean isCorrectionEnabled = tileEntity.isTransferCorrectionEnabled(side, pipeIndex);
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        connectionLoop:
        for (PipeTileEntity.Connection connection : connections) {
            if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                continue;
            }
            IChemicalHandler destination = connection.getChemicalHandler();
            if (destination == null) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
                continue;
            }
            boolean hasInsertedIntoDest = false;
            boolean destRejected = false;
            boolean keepTrying = true;
            int loopCount = 0;
            while (keepTrying && mbToTransfer > 0L && ++loopCount <= 1000) {
                boolean madeProgressThisPass = false;
                for (int i = 0; i < totalTanks; i++) {
                    if (mbToTransfer <= 0L) {
                        break connectionLoop;
                    }
                    ChemicalStack simulatedExtract = simulateExtract(gasHandler, i, mbToTransfer);
                    if (simulatedExtract.isEmpty()) {
                        continue;
                    }
                    if (FilterMatcher.canInsertGas(this, connection, simulatedExtract, filters) == isBlacklist) {
                        destRejected = true;
                        continue;
                    }
                    ChemicalStack leftOverSim = destination.insertChemical(simulatedExtract, Action.SIMULATE);
                    long canInsert = simulatedExtract.getAmount() - leftOverSim.getAmount();
                    if (canInsert > 0L) {
                        ChemicalStack actuallyExtracted = gasHandler.extractChemical(new ChemicalStack(simulatedExtract.getChemical(), canInsert), Action.EXECUTE);
                        if (actuallyExtracted.isEmpty()) {
                            continue;
                        }
                        destination.insertChemical(actuallyExtracted, Action.EXECUTE);
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
     * 模拟抽取指定化学储罐，空罐时按任意化学品回退
     */
    private ChemicalStack simulateExtract(IChemicalHandler gasHandler, int tank, long amount) {
        ChemicalStack gasInTank = gasHandler.getChemicalInTank(tank);
        if (!gasInTank.isEmpty()) {
            ChemicalStack simulated = gasHandler.extractChemical(new ChemicalStack(gasInTank.getChemical(), amount), Action.SIMULATE);
            if (!simulated.isEmpty()) {
                return simulated;
            }
        }
        if (tank == 0) {
            return gasHandler.extractChemical(amount, Action.SIMULATE);
        }
        return ChemicalStack.EMPTY;
    }

    @Override
    public boolean isCustomFrequencyEnabled() {
        return Main.SERVER_CONFIG == null || Main.SERVER_CONFIG.enableGasCustomFrequency.get();
    }

    @Override
    public int getMaxSleepTicks() {
        if (Main.SERVER_CONFIG == null) {
            return 40;
        }
        return Main.SERVER_CONFIG.gasMaxSleepTicks.get();
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.gasPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.gasPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.gasPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.gasPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.gasPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<GasData> getDataComponentType() {
        return ModItems.GAS_DATA_COMPONENT.get();
    }

    private static final GasData DEFAULT = new GasData(UpgradeTileEntity.FilterMode.WHITELIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.ROUND_ROBIN, Collections.emptyList());

    @Override
    public GasData defaultData() {
        return DEFAULT;
    }

}
