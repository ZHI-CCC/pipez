package de.maxhenkel.pipez.gui;

import de.maxhenkel.corelib.inventory.ScreenBase;
import de.maxhenkel.corelib.tag.SingleElementTag;
import de.maxhenkel.pipez.*;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.types.PipeType;
import de.maxhenkel.pipez.net.*;
import de.maxhenkel.pipez.utils.GasUtils;
import de.maxhenkel.pipez.utils.LastTabMemory;
import de.maxhenkel.pipez.utils.NbtUtils;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class ExtractScreen extends ScreenBase<ExtractContainer> {

    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/container/extract.png");

    private CycleIconButton redstoneButton;
    private CycleIconButton sortButton;
    private CycleIconButton filterButton;

    private Button addFilterButton;
    private Button editFilterButton;
    private Button removeFilterButton;

    private HoverArea redstoneArea;
    private HoverArea sortArea;
    private HoverArea filterArea;


    private HoverArea[] tabs;
    private PipeType<?, ?>[] pipeTypes;
    private int currentindex;

    @Nullable
    private EditBox freqEditBox;
    @Nullable
    private Item lastUpgradeItem;

    private FilterList filterList;

    public ExtractScreen(ExtractContainer container, Inventory playerInventory, Component title) {
        super(BACKGROUND, container, playerInventory, title);
        imageWidth = 176;
        imageHeight = 196;

        pipeTypes = container.getPipe().getPipeTypes();
        if (pipeTypes.length > 1) {
            tabs = new HoverArea[pipeTypes.length];
        }
        currentindex = container.getIndex();
        if (currentindex < 0) {
            currentindex = getMenu().getPipe().getPreferredPipeIndex(getMenu().getSide());
            if (Main.CLIENT_CONFIG != null && Main.CLIENT_CONFIG.rememberLastTab.get()) {
                Integer remembered = LastTabMemory.get(getMenu().getPipe(), getMenu().getSide());
                if (remembered != null && remembered >= 0 && remembered < pipeTypes.length) {
                    currentindex = remembered;
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        hoverAreas.clear();
        clearWidgets();

        PipeLogicTileEntity pipe = getMenu().getPipe();
        Direction side = getMenu().getSide();

        filterList = new FilterList(this, 32, 8, 136, 66, () -> pipe.getFilters(side, pipeTypes[currentindex]));

        Supplier<Integer> redstoneModeIndex = () -> pipe.getRedstoneMode(getMenu().getSide(), pipeTypes[currentindex]).ordinal();
        List<CycleIconButton.Icon> redstoneModeIcons = Arrays.asList(new CycleIconButton.Icon(BACKGROUND, 176, 16), new CycleIconButton.Icon(BACKGROUND, 192, 16), new CycleIconButton.Icon(BACKGROUND, 208, 16), new CycleIconButton.Icon(BACKGROUND, 224, 16));
        redstoneButton = new CycleIconButton(leftPos + 7, topPos + 7, redstoneModeIcons, redstoneModeIndex, button -> {
            PacketDistributor.sendToServer(new CycleRedstoneModeMessage(currentindex));
        });
        Supplier<Integer> distributionIndex = () -> pipe.getDistribution(getMenu().getSide(), pipeTypes[currentindex]).ordinal();
        List<CycleIconButton.Icon> distributionIcons = Arrays.asList(new CycleIconButton.Icon(BACKGROUND, 176, 0), new CycleIconButton.Icon(BACKGROUND, 192, 0), new CycleIconButton.Icon(BACKGROUND, 208, 0), new CycleIconButton.Icon(BACKGROUND, 224, 0));
        sortButton = new CycleIconButton(leftPos + 7, topPos + 31, distributionIcons, distributionIndex, button -> {
            PacketDistributor.sendToServer(new CycleDistributionMessage(currentindex));
        });
        Supplier<Integer> filterModeIndex = () -> pipeTypes[currentindex].hasFilter() ? pipe.getFilterMode(getMenu().getSide(), pipeTypes[currentindex]).ordinal() : 0;
        List<CycleIconButton.Icon> filterModeIcons = Arrays.asList(new CycleIconButton.Icon(BACKGROUND, 176, 32), new CycleIconButton.Icon(BACKGROUND, 192, 32));
        filterButton = new CycleIconButton(leftPos + 7, topPos + 55, filterModeIcons, filterModeIndex, button -> {
            PacketDistributor.sendToServer(new CycleFilterModeMessage(currentindex));
        });
        addFilterButton = Button.builder(Component.translatable("message.pipez.filter.add"), button -> {
            PacketDistributor.sendToServer(new EditFilterMessage(pipeTypes[currentindex].createFilter(), currentindex));
        }).bounds(leftPos + 31, topPos + 79, 40, 20).build();
        editFilterButton = Button.builder(Component.translatable("message.pipez.filter.edit"), button -> {
            if (filterList.getSelected() >= 0) {
                PacketDistributor.sendToServer(new EditFilterMessage(pipe.getFilters(side, pipeTypes[currentindex]).get(filterList.getSelected()), currentindex));
            }
        }).bounds(leftPos + 80, topPos + 79, 40, 20).build();
        removeFilterButton = Button.builder(Component.translatable("message.pipez.filter.remove"), button -> {
            if (filterList.getSelected() >= 0) {
                PacketDistributor.sendToServer(new RemoveFilterMessage(pipe.getFilters(side, pipeTypes[currentindex]).get(filterList.getSelected()).getId(), currentindex));
            }
        }).bounds(leftPos + 129, topPos + 79, 40, 20).build();

        addRenderableWidget(redstoneButton);
        addRenderableWidget(sortButton);
        addRenderableWidget(filterButton);
        addRenderableWidget(addFilterButton);
        addRenderableWidget(editFilterButton);
        addRenderableWidget(removeFilterButton);

        if (hasTabs()) {
            for (int i = 0; i < pipeTypes.length; i++) {
                int tabIndex = i;
                tabs[i] = new HoverArea(-26 + 3, 5 + 25 * i, 24, 24, () -> {
                    List<FormattedCharSequence> tooltip = new ArrayList<>();
                    tooltip.add(Component.translatable(pipeTypes[tabIndex].getTranslationKey()).getVisualOrderText());
                    return tooltip;
                });
                hoverAreas.add(tabs[i]);
            }
        }

        redstoneArea = new HoverArea(7, 7, 20, 20, () -> {
            if (redstoneButton.active) {
                return Arrays.asList(Component.translatable("tooltip.pipez.redstone_mode", Component.translatable("tooltip.pipez.redstone_mode." + pipe.getRedstoneMode(side, pipeTypes[currentindex]).getName())).getVisualOrderText());
            } else {
                return Collections.emptyList();
            }
        });
        sortArea = new HoverArea(7, 31, 20, 20, () -> {
            if (sortButton.active) {
                return Arrays.asList(Component.translatable("tooltip.pipez.distribution", Component.translatable("tooltip.pipez.distribution." + pipe.getDistribution(side, pipeTypes[currentindex]).getName())).getVisualOrderText());
            } else {
                return Collections.emptyList();
            }
        });
        filterArea = new HoverArea(7, 55, 20, 20, () -> {
            if (filterButton.active) {
                return Arrays.asList(Component.translatable("tooltip.pipez.filter_mode", Component.translatable("tooltip.pipez.filter_mode." + pipe.getFilterMode(side, pipeTypes[currentindex]).getName())).getVisualOrderText());
            } else {
                return Collections.emptyList();
            }
        });
        hoverAreas.add(redstoneArea);
        hoverAreas.add(sortArea);
        hoverAreas.add(filterArea);

        checkButtons();

        addFrequencyPanel();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        checkButtons();
        filterList.tick();
        tickFrequencyPanel();
    }

    private void checkButtons() {
        Upgrade upgrade = getMenu().getPipe().getUpgrade(getMenu().getSide());
        redstoneButton.active = Upgrade.canChangeRedstoneMode(upgrade);
        sortButton.active = Upgrade.canChangeDistributionMode(upgrade);
        filterButton.active = Upgrade.canChangeFilter(upgrade) && pipeTypes[currentindex].hasFilter();
        addFilterButton.active = filterButton.active;
        editFilterButton.active = Upgrade.canChangeFilter(upgrade) && filterList.getSelected() >= 0;
        removeFilterButton.active = editFilterButton.active;
    }

    /**
     * 在界面顶部构建抽取频率与开关面板
     */
    private void addFrequencyPanel() {
        freqEditBox = null;
        PipeLogicTileEntity pipe = getMenu().getPipe();
        Direction dir = getMenu().getSide();
        if (currentindex < 0 || currentindex >= pipeTypes.length) {
            return;
        }
        PipeType<?, ?> currentPipeType = pipeTypes[currentindex];
        if (!currentPipeType.isCustomFrequencyEnabled()) {
            return;
        }
        int currentFreq = pipe.getEffectiveFrequency(dir, currentindex);
        Component correctionText = Component.translatable("gui.pipez.transfer_correction");
        Component smartSleepText = Component.translatable("gui.pipez.smart_sleep");
        boolean hasCorrectionFeature = currentPipeType.supportsTransferCorrection();
        boolean showSmartSleep = Main.SERVER_CONFIG != null && Main.SERVER_CONFIG.showSmartSleepToggle.get() && currentPipeType.getMaxSleepTicks() > 0;
        int visibleSwitches = (hasCorrectionFeature ? 1 : 0) + (showSmartSleep ? 1 : 0);
        boolean singleRow = visibleSwitches <= 1;
        int panelX = leftPos;
        int panelY = singleRow ? topPos - 25 : topPos - 45;
        int panelHeight = singleRow ? 22 : 42;
        int freqPartWidth = 98;
        int correctionCheckboxWidth = hasCorrectionFeature ? font.width(correctionText) + 4 + 12 : 0;
        int smartSleepCheckboxWidth = showSmartSleep ? font.width(smartSleepText) + 4 + 12 : 0;
        int dividerGap = singleRow && visibleSwitches > 0 ? 16 : 0;
        int panelWidth;
        if (singleRow) {
            panelWidth = freqPartWidth + dividerGap + correctionCheckboxWidth + smartSleepCheckboxWidth + 6;
        } else {
            int row2Width = correctionCheckboxWidth + smartSleepCheckboxWidth + (hasCorrectionFeature && showSmartSleep ? 8 : 0);
            panelWidth = Math.max(freqPartWidth, row2Width) + 10;
        }
        int x = panelX;
        int y = panelY;
        int width = panelWidth;
        int height = panelHeight;
        addRenderableWidget(new TechPanel(x, y, width, height, singleRow, visibleSwitches, freqPartWidth, dividerGap));
        if (hasCorrectionFeature) {
            boolean isCorrectionOn = pipe.isTransferCorrectionEnabled(dir, currentindex);
            int correctionX = singleRow ? panelX + freqPartWidth + dividerGap : panelX + 4;
            int correctionY = singleRow ? panelY + 3 : panelY + 24;
            int correctionHeight = singleRow ? 16 : 14;
            TechCheckbox correctionButton = new TechCheckbox(correctionX, correctionY, correctionCheckboxWidth, correctionHeight, correctionText, isCorrectionOn, checked -> {
                PacketDistributor.sendToServer(new ToggleTransferCorrectionMessage(currentindex, checked));
                pipe.setTransferCorrectionEnabled(dir, currentindex, checked);
            });
            correctionButton.setTooltip(Tooltip.create(Component.translatable("tooltip.pipez.transfer_correction")));
            addRenderableWidget(correctionButton);
        }
        if (showSmartSleep) {
            boolean isSmartSleepOff = pipe.isSmartSleepDisabled(dir, currentindex);
            int sleepX = singleRow ? panelX + freqPartWidth + dividerGap : (hasCorrectionFeature ? panelX + 4 + correctionCheckboxWidth + 8 : panelX + 4);
            int sleepY = singleRow ? panelY + 3 : panelY + 24;
            int sleepHeight = singleRow ? 16 : 14;
            TechCheckbox smartSleepButton = new TechCheckbox(sleepX, sleepY, smartSleepCheckboxWidth, sleepHeight, smartSleepText, !isSmartSleepOff, checked -> {
                pipe.setSmartSleepDisabled(dir, currentindex, !checked);
                PacketDistributor.sendToServer(new ToggleSmartSleepMessage(currentindex, !checked));
            });
            smartSleepButton.setTooltip(Tooltip.create(Component.translatable("tooltip.pipez.smart_sleep")));
            addRenderableWidget(smartSleepButton);
        }
        StringWidget label = new StringWidget(panelX + 4, panelY + 6, 50, 10, Component.translatable("gui.pipez.extract_freq"), font);
        label.setColor(0xFFFF55);
        addRenderableWidget(label);
        EditBox editBox = new EditBox(font, panelX + 54, panelY + 3, 30, 16, Component.literal("Freq"));
        editBox.setValue(String.valueOf(currentFreq));
        editBox.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        editBox.setResponder(this::onFrequencyChanged);
        addRenderableWidget(editBox);
        freqEditBox = editBox;
        lastUpgradeItem = pipe.getUpgradeItem(dir).getItem();
        int btnX = panelX + 86;
        addRenderableWidget(new TinyOperationButton(btnX, panelY + 3, true, button -> editBox.setValue(String.valueOf(Math.min(PipeTileEntity.MAX_CUSTOM_FREQUENCY, parseFrequency(editBox.getValue()) + 1)))));
        addRenderableWidget(new TinyOperationButton(btnX, panelY + 12, false, button -> editBox.setValue(String.valueOf(Math.max(minFrequency(), parseFrequency(editBox.getValue()) - 1)))));
    }

    /**
     * 升级物品变化时刷新频率显示为新的原生频率
     */
    private void tickFrequencyPanel() {
        if (freqEditBox == null) {
            return;
        }
        PipeLogicTileEntity pipe = getMenu().getPipe();
        Direction dir = getMenu().getSide();
        Item currentUpgrade = pipe.getUpgradeItem(dir).getItem();
        if (currentUpgrade == lastUpgradeItem) {
            return;
        }
        lastUpgradeItem = currentUpgrade;
        if (currentindex < 0 || currentindex >= pipeTypes.length) {
            return;
        }
        int newDefaultFrequency = pipeTypes[currentindex].getDefaultFrequency(pipe.getUpgrade(dir));
        if (parseFrequency(freqEditBox.getValue()) != newDefaultFrequency) {
            freqEditBox.setValue(String.valueOf(newDefaultFrequency));
        }
    }

    private int minFrequency() {
        if (Main.SERVER_CONFIG == null || !Main.SERVER_CONFIG.restrictFrequencyToNative.get()) {
            return 1;
        }
        PipeLogicTileEntity pipe = getMenu().getPipe();
        return pipeTypes[currentindex].getDefaultFrequency(pipe.getUpgrade(getMenu().getSide()));
    }

    private void onFrequencyChanged(String val) {
        if (val.isEmpty()) {
            return;
        }
        PipeLogicTileEntity pipe = getMenu().getPipe();
        Direction dir = getMenu().getSide();
        if (currentindex < 0 || currentindex >= pipeTypes.length) {
            return;
        }
        int defaultFrequency = pipeTypes[currentindex].getDefaultFrequency(pipe.getUpgrade(dir));
        int frequency = Math.min(PipeTileEntity.MAX_CUSTOM_FREQUENCY, Math.max(minFrequency(), parseFrequency(val)));
        int frequencyToSend = frequency == defaultFrequency ? 0 : frequency;
        PacketDistributor.sendToServer(new SetFrequencyMessage(currentindex, frequencyToSend));
        pipe.setCustomFrequency(dir, currentindex, frequencyToSend);
    }

    private int parseFrequency(String val) {
        if (val == null || val.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderLabels(guiGraphics, mouseX, mouseY);
        guiGraphics.drawString(font, playerInventoryTitle.getVisualOrderText(), 8F, (float) (imageHeight - 96 + 3), FONT_COLOR, false);

        filterList.drawGuiContainerForegroundLayer(guiGraphics, mouseX, mouseY);

        drawHoverAreas(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTicks, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTicks, mouseX, mouseY);
        filterList.drawGuiContainerBackgroundLayer(guiGraphics, partialTicks, mouseX, mouseY);

        if (hasTabs()) {
            for (int i = 0; i < pipeTypes.length; i++) {
                if (i == currentindex) {
                    guiGraphics.blit(BACKGROUND, leftPos - 26 + 3, topPos + 5 + 25 * i, 176, 48, 26, 24);
                } else {
                    guiGraphics.blit(BACKGROUND, leftPos - 26 + 3, topPos + 5 + 25 * i, 176, 72, 26, 24);
                }
            }
            for (int i = 0; i < pipeTypes.length; i++) {
                if (i == currentindex) {
                    guiGraphics.renderItem(pipeTypes[i].getIcon(), leftPos - 26 + 3 + 4, topPos + 5 + 25 * i + 4, 0);
                    guiGraphics.renderItemDecorations(font, pipeTypes[i].getIcon(), leftPos - 26 + 3 + 4, topPos + 5 + 25 * i + 4);
                } else {
                    guiGraphics.renderItem(pipeTypes[i].getIcon(), leftPos - 26 + 3 + 4 + 2, topPos + 5 + 25 * i + 4, 0);
                    guiGraphics.renderItemDecorations(font, pipeTypes[i].getIcon(), leftPos - 26 + 3 + 4 + 2, topPos + 5 + 25 * i + 4);
                }
            }
        }
    }

    public int getTabsX() {
        return leftPos - getTabsWidth();
    }

    public int getTabsY() {
        return topPos + 5;
    }

    public int getTabsHeight() {
        if (hasTabs()) {
            return 25 * tabs.length;
        } else {
            return 0;
        }
    }

    public int getTabsWidth() {
        return 26 - 3;
    }

    public boolean hasTabs() {
        return tabs != null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (filterList.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (hasTabs()) {
            for (int i = 0; i < tabs.length; i++) {
                HoverArea hoverArea = tabs[i];
                if (currentindex != i && hoverArea.isHovered(leftPos, topPos, (int) mouseX, (int) mouseY)) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1F));
                    currentindex = i;
                    if (Main.CLIENT_CONFIG != null && Main.CLIENT_CONFIG.rememberLastTab.get()) {
                        LastTabMemory.put(getMenu().getPipe(), getMenu().getSide(), currentindex);
                    }
                    init();
                    return true;
                }
            }
        }

        if (hasShiftDown()) {
            Slot sl = this.getSlotUnderMouse();
            if (sl != null && !(sl instanceof UpgradeSlot)) {
                addQuickFilter(sl.getItem());
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    public void addQuickFilter(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        if (!filterButton.active) {
            return;
        }

        Filter<?, ?> filter = pipeTypes[currentindex].createFilter();
        filter.setExactMetadata(true);

        if (filter instanceof ItemFilter) {
            filter.setTag(new SingleElementTag(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getItem()));
            filter.setMetadata(NbtUtils.componentPatchToNbtOptional(stack.getComponentsPatch()).orElse(null));
            PacketDistributor.sendToServer(new UpdateFilterMessage(filter, currentindex));
        } else if (filter instanceof FluidFilter) {
            FluidUtil.getFluidContained(stack).ifPresent(s -> {
                filter.setTag(new SingleElementTag(BuiltInRegistries.FLUID.getKey(s.getFluid()), s.getFluid()));
                filter.setMetadata(NbtUtils.componentPatchToNbtOptional(stack.getComponentsPatch()).orElse(null));
                PacketDistributor.sendToServer(new UpdateFilterMessage(filter, currentindex));
            });
        } else if (filter instanceof GasFilter) {
            ChemicalStack gas = GasUtils.getGasContained(stack);
            if (gas != null) {
                filter.setTag(new SingleElementTag(gas.getChemical().getRegistryName(), gas.getChemical()));
                filter.setMetadata(null);
                PacketDistributor.sendToServer(new UpdateFilterMessage(filter, currentindex));
            }
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (filterList.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (filterList.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
            return true;
        }
        return false;
    }

    @Override
    public <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        return super.addRenderableWidget(widget);
    }

    public void addHoverArea(HoverArea hoverArea) {
        hoverAreas.add(hoverArea);
    }

    /**
     * 频率面板的背景面板
     */
    private static class TechPanel extends AbstractWidget {

        private final boolean singleRow;
        private final int visibleSwitches;
        private final int freqPartWidth;
        private final int dividerGap;

        public TechPanel(int x, int y, int width, int height, boolean singleRow, int visibleSwitches, int freqPartWidth, int dividerGap) {
            super(x, y, width, height, Component.empty());
            this.singleRow = singleRow;
            this.visibleSwitches = visibleSwitches;
            this.freqPartWidth = freqPartWidth;
            this.dividerGap = dividerGap;
            active = false;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int borderColor = 0xFF555555;
            int innerColor = 0xDD202020;
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, borderColor);
            guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, innerColor);
            if (singleRow) {
                if (visibleSwitches > 0) {
                    int dividerX = getX() + freqPartWidth + dividerGap / 2;
                    guiGraphics.fill(dividerX, getY() + 3, dividerX + 1, getY() + height - 3, borderColor);
                }
            } else {
                int dividerY = getY() + 21;
                guiGraphics.fill(getX() + 4, dividerY, getX() + width - 4, dividerY + 1, borderColor);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
