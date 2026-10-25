package com.sparrowwallet.drongo.evm;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * An ERC-20 token on an EVM chain: its contract address and the metadata read from it. Symbol and name come from
 * the contract, which anyone can deploy, so they are display hints only and are sanitised; the contract address is
 * the token's identity.
 */
public record Erc20Token(EvmAddress contract, String symbol, String name, int decimals) {
    public static final int MAX_DECIMALS = 36;

    public Erc20Token {
        if(decimals < 0 || decimals > MAX_DECIMALS) {
            throw new IllegalArgumentException("Token decimals out of range: " + decimals);
        }
        symbol = sanitise(symbol, 16);
        name = sanitise(name, 64);
    }

    /** Printable characters only, trimmed and length-limited, so a hostile contract cannot spoof UI text. */
    static String sanitise(String text, int maxLength) {
        if(text == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        text.codePoints().filter(c -> c >= 0x20 && c != 0x7f && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .forEach(clean::appendCodePoint);
        String trimmed = clean.toString().trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /** A base-unit amount as a plain decimal string, e.g. 150000000 with 8 decimals -> "1.5". */
    public String format(BigInteger units) {
        BigDecimal amount = new BigDecimal(units).movePointLeft(decimals).stripTrailingZeros();
        return amount.signum() == 0 ? "0" : amount.toPlainString();
    }

    /** Parses a whole-token amount into base units; rejects negatives and more decimal places than the token has. */
    public BigInteger parse(String amount) {
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim());
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Not an amount: " + amount);
        }
        if(value.signum() < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
        BigDecimal units = value.movePointRight(decimals);
        if(units.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(symbol + " amounts have at most " + decimals + " decimal places");
        }
        return units.toBigIntegerExact();
    }
}
