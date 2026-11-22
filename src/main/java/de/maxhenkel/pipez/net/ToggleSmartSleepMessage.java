package de.maxhenkel.pipez.net;

import de.maxhenkel.corelib.net.Message;
import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.gui.ExtractContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ToggleSmartSleepMessage implements Message<ToggleSmartSleepMessage> {

    public static final CustomPacketPayload.Type<ToggleSmartSleepMessage> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Main.MODID, "toggle_smart_sleep"));

    private int index;
    private boolean disabled;

    public ToggleSmartSleepMessage() {

    }

    public ToggleSmartSleepMessage(int index, boolean disabled) {
        this.index = index;
        this.disabled = disabled;
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
        if (Main.SERVER_CONFIG == null || !Main.SERVER_CONFIG.showSmartSleepToggle.get()) {
            return;
        }
        if (!(sender.containerMenu instanceof ExtractContainer extractContainer)) {
            return;
        }
        PipeLogicTileEntity pipe = extractContainer.getPipe();
        if (index < 0 || index >= pipe.getPipeTypes().length) {
            return;
        }
        if (!pipe.isExtracting(extractContainer.getSide())) {
            return;
        }
        pipe.setSmartSleepDisabled(extractContainer.getSide(), index, disabled);
        pipe.setChanged();
        pipe.syncData();
    }

    @Override
    public ToggleSmartSleepMessage fromBytes(RegistryFriendlyByteBuf packetBuffer) {
        this.index = packetBuffer.readInt();
        this.disabled = packetBuffer.readBoolean();
        return this;
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf packetBuffer) {
        packetBuffer.writeInt(index);
        packetBuffer.writeBoolean(disabled);
    }

    @Override
    public Type<ToggleSmartSleepMessage> type() {
        return TYPE;
    }

}
