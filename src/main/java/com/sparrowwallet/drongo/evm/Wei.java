package com.sparrowwallet.drongo.evm;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Converts between wei (the 10^-18 base unit) and whole-token amounts such as zkLTC. */
public final class Wei {
    public static final int DECIMALS = 18;
    private static final BigDecimal ONE_TOKEN = BigDecimal.TEN.pow(DECIMALS);
    private static final BigInteger ONE_GWEI = BigInteger.TEN.pow(9);

    private Wei() {}

    /** A plain decimal string with no trailing zeros, e.g. 1500000000000000000 wei -> "1.5". */
    public static String format(BigInteger wei) {
        BigDecimal amount = new BigDecimal(wei).divide(ONE_TOKEN).stripTrailingZeros();
        return amount.signum() == 0 ? "0" : amount.toPlainString();
    }

    /** Parses a whole-token amount such as "0.25" into wei; rejects negatives and more than 18 decimals. */
    public static BigInteger parse(String amount) {
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim());
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Not an amount: " + amount);
        }
        if(value.signum() < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
        BigDecimal wei = value.multiply(ONE_TOKEN);
        if(wei.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Amounts have at most " + DECIMALS + " decimal places");
        }
        return wei.toBigIntegerExact();
    }

    public static BigInteger gwei(long gwei) {
        return BigInteger.valueOf(gwei).multiply(ONE_GWEI);
    }
}
