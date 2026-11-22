package de.maxhenkel.pipez.events;

import de.maxhenkel.pipez.Main;
import de.maxhenkel.pipez.Upgrade;
import de.maxhenkel.pipez.blocks.tileentity.types.EnergyPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.FluidPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.GasPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.ItemPipeType;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import de.maxhenkel.pipez.items.UpgradeItem;
import de.maxhenkel.pipez.utils.MekanismUtils;
import de.maxhenkel.pipez.utils.RateTooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 客户端升级物品的工具提示，按住 Shift 显示精确数值，平时显示紧凑数值
 */
@EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT)
public class TooltipEventHandler {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof UpgradeItem upgradeItem)) {
            return;
        }
        Upgrade tier = upgradeItem.getTier();
        boolean showExact = Screen.hasShiftDown();
        for (PipeType<?, ?> pipeType : getRateTooltipTypes()) {
            long rate = pipeType.getRate(tier);
            int frequency = pipeType.getDefaultFrequency(tier);
            event.getToolTip().add(RateTooltip.format(pipeType.getRateTranslationKey(), RateTooltip.formatNumber(rate, !showExact), frequency));
        }
        if (!showExact) {
            event.getToolTip().add(Component.translatable("tooltip.pipez.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 工具提示覆盖的管道类型，未安装 Mekanism 时不包含气体
     */
    private static PipeType<?, ?>[] getRateTooltipTypes() {
        if (MekanismUtils.isMekanismInstalled()) {
            return new PipeType[]{ItemPipeType.INSTANCE, FluidPipeType.INSTANCE, EnergyPipeType.INSTANCE, GasPipeType.INSTANCE};
        }
        return new PipeType[]{ItemPipeType.INSTANCE, FluidPipeType.INSTANCE, EnergyPipeType.INSTANCE};
    }

}
