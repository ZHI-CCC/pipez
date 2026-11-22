package de.maxhenkel.pipez.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 科技风格的复选框控件，文本在左侧，勾选框在右侧
 */
public class TechCheckbox extends AbstractWidget {

    private boolean checked;
    private final Consumer<Boolean> onToggle;
    private final Font font;

    public TechCheckbox(int x, int y, int width, int height, Component message, boolean initialState, Consumer<Boolean> onToggle) {
        super(x, y, width, height, message);
        this.checked = initialState;
        this.onToggle = onToggle;
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        checked = !checked;
        playDownSound(Minecraft.getInstance().getSoundManager());
        onToggle.accept(checked);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int textY = getY() + 3;
        int textColor = 0x55FFFF;
        guiGraphics.drawString(font, getMessage(), getX(), textY, textColor, false);
        int boxSize = 12;
        int boxX = getX() + font.width(getMessage()) + 4;
        int boxY = getY() + 2;
        int borderColor = 0xFF555555;
        int bgColor = isHovered() ? 0xFF444444 : 0xFF222222;
        guiGraphics.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, borderColor);
        guiGraphics.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1, bgColor);
        if (checked) {
            int fillColor = 0xFF55FF55;
            guiGraphics.fill(boxX + 3, boxY + 3, boxX + boxSize - 3, boxY + boxSize - 3, fillColor);
        }
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.enableBlend();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
