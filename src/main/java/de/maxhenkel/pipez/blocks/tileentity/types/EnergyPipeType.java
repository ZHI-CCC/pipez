package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.pipez.Filter;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.Upgrade;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.datacomponents.EnergyData;
import de.maxhenkel.pipez.items.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EnergyPipeType extends PipeType<Void, EnergyData> {

    public static final EnergyPipeType INSTANCE = new EnergyPipeType();

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return Capabilities.EnergyStorage.BLOCK;
    }

    @Nullable
    @Override
    public Filter<?, Void> createFilter() {
        return null;
    }

    @Override
    public boolean hasFilter() {
        return false;
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.energy";
    }

    @Override
    public String getRateTranslationKey() {
        return "tooltip.pipez.transfer_rate.energy";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.ENERGY_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.energy", getRate(upgrade));
    }

    @Override
    public void tick(PipeLogicTileEntity tileEntity) {

    }

    public void pullEnergy(PipeLogicTileEntity tileEntity, Direction side) {
        if (!tileEntity.isExtracting(side)) {
            return;
        }
        if (!tileEntity.shouldWork(side, this)) {
            return;
        }
        PipeTileEntity.Connection extractingConnection = tileEntity.getExtractingConnection(side);
        if (extractingConnection == null) {
            return;
        }
        IEnergyStorage energyStorage = extractingConnection.getEnergyHandler();
        if (energyStorage == null || !energyStorage.canExtract()) {
            return;
        }

        List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

        if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
            insertEqually(tileEntity, side, connections, energyStorage);
        } else {
            insertOrdered(tileEntity, side, connections, energyStorage);
        }
    }

    public int receive(PipeLogicTileEntity tileEntity, Direction side, int amount, boolean simulate) {
        if (!tileEntity.isExtracting(side)) {
            return 0;
        }
        if (!tileEntity.shouldWork(side, this)) {
            return 0;
        }

        List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

        int maxTransfer = Math.min(getRate(tileEntity, side), amount);

        if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
            return receiveEqually(tileEntity, side, connections, maxTransfer, simulate);
        } else {
            return receiveOrdered(tileEntity, side, connections, maxTransfer, simulate);
        }
    }

    protected void insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IEnergyStorage energyStorage) {
        int pipeIndex = tileEntity.getIndex(this);
        ArrayList<PipeTileEntity.Connection> awakeConnections = filterAwake(tileEntity, side, connections, pipeIndex);
        if (awakeConnections.isEmpty()) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int energyToTransfer = getRate(tileEntity, side);
        if (energyToTransfer <= 0) {
            return;
        }
        int simulatedExtract = energyStorage.extractEnergy(energyToTransfer, true);
        if (simulatedExtract <= 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int targetCount = awakeConnections.size();
        IEnergyStorage[] validHandlers = new IEnergyStorage[targetCount];
        PipeTileEntity.Connection[] validConnections = new PipeTileEntity.Connection[targetCount];
        int validTargets = 0;
        for (PipeTileEntity.Connection connection : awakeConnections) {
            IEnergyStorage destination = connection.getEnergyHandler();
            if (destination == null || !destination.canReceive()) {
                continue;
            }
            validHandlers[validTargets] = destination;
            validConnections[validTargets] = connection;
            validTargets++;
        }
        if (validTargets == 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int baseAmount = simulatedExtract / validTargets;
        int remainder = simulatedExtract % validTargets;
        int successfullyExtracted = 0;
        int p = tileEntity.getRoundRobinIndex(side, this);
        int startIndex = p % validTargets;
        for (int j = 0; j < validTargets; j++) {
            int targetIndex = (startIndex + j) % validTargets;
            int amountToSend = baseAmount + (j < remainder ? 1 : 0);
            if (amountToSend <= 0) {
                continue;
            }
            int canInsert = validHandlers[targetIndex].receiveEnergy(amountToSend, true);
            if (canInsert > 0) {
                int actuallyExtracted = energyStorage.extractEnergy(canInsert, false);
                if (actuallyExtracted > 0) {
                    validHandlers[targetIndex].receiveEnergy(actuallyExtracted, false);
                    successfullyExtracted += actuallyExtracted;
                    tileEntity.markDestinationSuccessful(side, validConnections[targetIndex].getPos(), pipeIndex);
                    continue;
                }
                tileEntity.markDestinationPenalty(side, validConnections[targetIndex].getPos(), pipeIndex);
            } else {
                tileEntity.markDestinationPenalty(side, validConnections[targetIndex].getPos(), pipeIndex);
            }
        }
        tileEntity.setRoundRobinIndex(side, this, (startIndex + remainder) % validTargets);
        if (successfullyExtracted > 0) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    protected int receiveEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, int maxReceive, boolean simulate) {
        int pipeIndex = tileEntity.getIndex(this);
        if (connections.isEmpty() || maxReceive <= 0) {
            if (!simulate) {
                tileEntity.addExtractionPenalty(side, pipeIndex);
            }
            return 0;
        }
        if (tileEntity.pushRecursion()) {
            return 0;
        }
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        int targetCount = connections.size();
        IEnergyStorage[] validHandlers = new IEnergyStorage[targetCount];
        PipeTileEntity.Connection[] validConnections = new PipeTileEntity.Connection[targetCount];
        int validTargets = 0;
        for (int i = 0; i < targetCount; i++) {
            PipeTileEntity.Connection connection = connections.get(i);
            if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                continue;
            }
            IEnergyStorage destination = connection.getEnergyHandler();
            if (destination == null || !destination.canReceive() || destination.receiveEnergy(maxReceive, true) <= 0) {
                continue;
            }
            validHandlers[validTargets] = destination;
            validConnections[validTargets] = connection;
            validTargets++;
        }
        if (validTargets == 0) {
            tileEntity.popRecursion();
            if (!simulate) {
                tileEntity.addExtractionPenalty(side, pipeIndex);
            }
            return 0;
        }
        int actuallyTransferred = 0;
        int baseAmount = maxReceive / validTargets;
        int remainder = maxReceive % validTargets;
        int p = tileEntity.getRoundRobinIndex(side, this);
        int startIndex = p % validTargets;
        for (int i = 0; i < validTargets; i++) {
            int targetIndex = (startIndex + i) % validTargets;
            int amountToSend = baseAmount + (i < remainder ? 1 : 0);
            if (amountToSend <= 0) {
                continue;
            }
            int received = validHandlers[targetIndex].receiveEnergy(amountToSend, simulate);
            actuallyTransferred += received;
            if (simulate) {
                continue;
            }
            if (received > 0) {
                tileEntity.markDestinationSuccessful(side, validConnections[targetIndex].getPos(), pipeIndex);
            } else {
                tileEntity.markDestinationPenalty(side, validConnections[targetIndex].getPos(), pipeIndex);
            }
        }
        tileEntity.popRecursion();
        if (!simulate) {
            tileEntity.setRoundRobinIndex(side, this, (startIndex + remainder) % validTargets);
            if (actuallyTransferred > 0) {
                tileEntity.markExtractionSuccessful(side, pipeIndex);
            } else {
                tileEntity.addExtractionPenalty(side, pipeIndex);
            }
        }
        return actuallyTransferred;
    }

    protected void insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IEnergyStorage energyStorage) {
        int pipeIndex = tileEntity.getIndex(this);
        ArrayList<PipeTileEntity.Connection> awakeConnections = filterAwake(tileEntity, side, connections, pipeIndex);
        if (awakeConnections.isEmpty()) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        int energyToTransfer = getRate(tileEntity, side);
        if (energyToTransfer <= 0) {
            return;
        }
        int simulatedExtract = energyStorage.extractEnergy(energyToTransfer, true);
        if (simulatedExtract <= 0) {
            tileEntity.addExtractionPenalty(side, pipeIndex);
            return;
        }
        boolean hasInsertedAny = false;
        int remainingToInsert = simulatedExtract;
        for (PipeTileEntity.Connection connection : awakeConnections) {
            if (remainingToInsert <= 0) {
                break;
            }
            IEnergyStorage destination = connection.getEnergyHandler();
            if (destination == null || !destination.canReceive()) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
                continue;
            }
            int canInsert = destination.receiveEnergy(remainingToInsert, true);
            if (canInsert > 0) {
                int actuallyExtracted = energyStorage.extractEnergy(canInsert, false);
                if (actuallyExtracted > 0) {
                    destination.receiveEnergy(actuallyExtracted, false);
                    remainingToInsert -= actuallyExtracted;
                    hasInsertedAny = true;
                    tileEntity.markDestinationSuccessful(side, connection.getPos(), pipeIndex);
                    continue;
                }
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
            } else {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
            }
        }
        if (hasInsertedAny) {
            tileEntity.markExtractionSuccessful(side, pipeIndex);
        } else {
            tileEntity.addExtractionPenalty(side, pipeIndex);
        }
    }

    protected int receiveOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, int maxReceive, boolean simulate) {
        int pipeIndex = tileEntity.getIndex(this);
        if (connections.isEmpty() || maxReceive <= 0) {
            if (!simulate) {
                tileEntity.addExtractionPenalty(side, pipeIndex);
            }
            return 0;
        }
        if (tileEntity.pushRecursion()) {
            return 0;
        }
        boolean sleepEnabled = getMaxSleepTicks() > 0;
        int actuallyTransferred = 0;
        int energyToTransfer = maxReceive;
        for (PipeTileEntity.Connection connection : connections) {
            if (energyToTransfer <= 0) {
                break;
            }
            if (sleepEnabled && !tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                continue;
            }
            IEnergyStorage destination = connection.getEnergyHandler();
            if (destination == null || !destination.canReceive()) {
                if (simulate) {
                    continue;
                }
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
                continue;
            }
            int extracted = destination.receiveEnergy(Math.min(energyToTransfer, maxReceive), simulate);
            if (extracted > 0) {
                energyToTransfer -= extracted;
                actuallyTransferred += extracted;
                if (simulate) {
                    continue;
                }
                tileEntity.markDestinationSuccessful(side, connection.getPos(), pipeIndex);
            } else if (!simulate) {
                tileEntity.markDestinationPenalty(side, connection.getPos(), pipeIndex);
            }
        }
        tileEntity.popRecursion();
        if (!simulate) {
            if (actuallyTransferred > 0) {
                tileEntity.markExtractionSuccessful(side, pipeIndex);
            } else {
                tileEntity.addExtractionPenalty(side, pipeIndex);
            }
        }
        return actuallyTransferred;
    }

    /**
     * 过滤出当前处于唤醒状态的连接
     */
    private ArrayList<PipeTileEntity.Connection> filterAwake(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, int pipeIndex) {
        ArrayList<PipeTileEntity.Connection> awakeConnections = new ArrayList<>(connections.size());
        if (getMaxSleepTicks() <= 0) {
            awakeConnections.addAll(connections);
            return awakeConnections;
        }
        for (PipeTileEntity.Connection connection : connections) {
            if (tileEntity.isDestinationAwake(side, connection.getPos(), pipeIndex)) {
                awakeConnections.add(connection);
            }
        }
        return awakeConnections;
    }

    @Override
    public boolean isCustomFrequencyEnabled() {
        return Main.SERVER_CONFIG == null || Main.SERVER_CONFIG.enableEnergyCustomFrequency.get();
    }

    @Override
    public int getMaxSleepTicks() {
        if (Main.SERVER_CONFIG == null) {
            return 5;
        }
        return Main.SERVER_CONFIG.energyMaxSleepTicks.get();
    }

    @Override
    public boolean supportsTransferCorrection() {
        return false;
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.energyPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.energyPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.energyPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.energyPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.energyPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<EnergyData> getDataComponentType() {
        return ModItems.ENERGY_DATA_COMPONENT.get();
    }

    private static final EnergyData DEFAULT = new EnergyData(UpgradeTileEntity.FilterMode.WHITELIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.ROUND_ROBIN, Collections.emptyList());

    @Override
    public EnergyData defaultData() {
        return DEFAULT;
    }
}
