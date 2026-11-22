package de.maxhenkel.pipez.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数值紧凑格式化，大于等于一千时按 k/M/G 等单位缩写
 */
public final class CompactNumbers {

    private static final String[] UNITS = {"", "k", "M", "G", "T", "P", "E"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

    private CompactNumbers() {
    }

    public static String format(long value) {
        if (value < 0L) {
            return "-" + format(-value);
        }
        if (value < 1000L) {
            return Long.toString(value);
        }
        long threshold = 1000L;
        int unit = 1;
        while (unit < UNITS.length - 1 && value >= threshold * 1000L) {
            threshold *= 1000L;
            unit++;
        }
        BigDecimal amount = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(threshold), 2, RoundingMode.HALF_UP);
        if (amount.compareTo(THOUSAND) >= 0 && unit < UNITS.length - 1) {
            unit++;
            amount = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(threshold).multiply(THOUSAND), 2, RoundingMode.HALF_UP);
        }
        long intDigits = amount.longValue();
        int decimals = intDigits >= 100L ? 0 : (intDigits >= 10L ? 1 : 2);
        BigDecimal result = amount.setScale(decimals, RoundingMode.HALF_UP);
        if (result.compareTo(THOUSAND) >= 0 && unit < UNITS.length - 1) {
            unit++;
            result = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(threshold).multiply(THOUSAND), 0, RoundingMode.HALF_UP);
        }
        return result.stripTrailingZeros().toPlainString() + UNITS[unit];
    }
}
