package de.maxhenkel.pipez.net;

import de.maxhenkel.corelib.net.Message;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import de.maxhenkel.pipez.gui.ExtractContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class SetFrequencyMessage implements Message<SetFrequencyMessage> {

    public static final CustomPacketPayload.Type<SetFrequencyMessage> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Main.MODID, "set_frequency"));

    private int index;
    private int frequency;

    public SetFrequencyMessage() {

    }

    public SetFrequencyMessage(int index, int frequency) {
        this.index = index;
        this.frequency = frequency;
    }

    @Override
    public PacketFlow getExecutingSide() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public void executeServerSide(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        if (!(sender.containerMenu instanceof ExtractContainer extractContainer)) {
            return;
        }
        PipeLogicTileEntity pipe = extractContainer.getPipe();
        if (index < 0 || index >= pipe.getPipeTypes().length) {
            return;
        }
        PipeType<?, ?> pipeType = pipe.getPipeTypes()[index];
        if (!pipeType.isCustomFrequencyEnabled()) {
            return;
        }
        if (!pipe.isExtracting(extractContainer.getSide())) {
            return;
        }
        int freq = Mth.clamp(frequency, 0, PipeTileEntity.MAX_CUSTOM_FREQUENCY);
        if (Main.SERVER_CONFIG != null && Main.SERVER_CONFIG.restrictFrequencyToNative.get()) {
            int defaultFrequency = pipeType.getDefaultFrequency(pipe.getUpgrade(extractContainer.getSide()));
            if (freq > 0 && freq < defaultFrequency) {
                freq = 0;
            }
        }
        pipe.setCustomFrequency(extractContainer.getSide(), index, freq);
        pipe.setChanged();
        pipe.syncData();
    }

    @Override
    public SetFrequencyMessage fromBytes(RegistryFriendlyByteBuf packetBuffer) {
        this.index = packetBuffer.readInt();
        this.frequency = packetBuffer.readInt();
        return this;
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf packetBuffer) {
        packetBuffer.writeInt(index);
        packetBuffer.writeInt(frequency);
    }

    @Override
    public Type<SetFrequencyMessage> type() {
        return TYPE;
    }

}
