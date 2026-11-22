package de.maxhenkel.pipez.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 微型加减操作按钮
 */
public class TinyOperationButton extends Button {

    private final boolean isPlus;

    public TinyOperationButton(int x, int y, boolean isPlus, Button.OnPress onPress) {
        super(x, y, 11, 8, Component.empty(), onPress, Button.DEFAULT_NARRATION);
        this.isPlus = isPlus;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int borderColor = 0xFF333333;
        int bgColor = isHovered() ? 0xFF777777 : 0xFF444444;
        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, borderColor);
        guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, bgColor);
        int iconColor = 0xFFFFFFFF;
        guiGraphics.fill(getX() + 3, getY() + 3, getX() + 8, getY() + 4, iconColor);
        if (isPlus) {
            guiGraphics.fill(getX() + 5, getY() + 1, getX() + 6, getY() + 6, iconColor);
        }
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.enableBlend();
    }
}
