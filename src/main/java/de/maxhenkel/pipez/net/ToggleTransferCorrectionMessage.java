package de.maxhenkel.pipez.net;

import de.maxhenkel.corelib.net.Message;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import de.maxhenkel.pipez.gui.ExtractContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ToggleTransferCorrectionMessage implements Message<ToggleTransferCorrectionMessage> {

    public static final CustomPacketPayload.Type<ToggleTransferCorrectionMessage> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Main.MODID, "toggle_transfer_correction"));

    private int index;
    private boolean enabled;

    public ToggleTransferCorrectionMessage() {

    }

    public ToggleTransferCorrectionMessage(int index, boolean enabled) {
        this.index = index;
        this.enabled = enabled;
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
        if (!pipeType.supportsTransferCorrection()) {
            return;
        }
        if (!pipe.isExtracting(extractContainer.getSide())) {
            return;
        }
        pipe.setTransferCorrectionEnabled(extractContainer.getSide(), index, enabled);
        pipe.setChanged();
        pipe.syncData();
    }

    @Override
    public ToggleTransferCorrectionMessage fromBytes(RegistryFriendlyByteBuf packetBuffer) {
        this.index = packetBuffer.readInt();
        this.enabled = packetBuffer.readBoolean();
        return this;
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf packetBuffer) {
        packetBuffer.writeInt(index);
        packetBuffer.writeBoolean(enabled);
    }

    @Override
    public Type<ToggleTransferCorrectionMessage> type() {
        return TYPE;
    }

}
