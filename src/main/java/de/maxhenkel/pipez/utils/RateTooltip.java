package de.maxhenkel.pipez.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 管道传输速率提示文本格式化
 */
public final class RateTooltip {

    private static final NumberFormat EXACT_FORMATTER = NumberFormat.getInstance(Locale.US);

    private RateTooltip() {
    }

    public static Component format(String rateTranslationKey, Object rate, Object frequency) {
        MutableComponent body = Component.translatable(rateTranslationKey, rate, frequency).withStyle(ChatFormatting.AQUA);
        String prefixKey = rateTranslationKey + ".prefix";
        if (!Language.getInstance().has(prefixKey)) {
            return body;
        }
        return Component.empty()
                .append(Component.translatable(prefixKey).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(body);
    }

    public static String formatNumber(long value, boolean compact) {
        return compact ? CompactNumbers.format(value) : EXACT_FORMATTER.format(value);
    }
}
