package com.sparrowwallet.drongo.evm;

import java.util.Objects;

/**
 * An ERC-721 collection: its contract address and what it says about itself. Name and symbol come from the contract,
 * which anyone can deploy, so they are display hints only and are sanitised as NFT metadata text is; the contract address
 * is the collection's identity.
 *
 * @param enumerable the contract claims ERC-721 Enumerable, so a holder's tokens can be listed by index
 * @param soulbound  the contract claims EIP-5192 (minimal soulbound): its tokens cannot be transferred
 */
public record Erc721Collection(EvmAddress contract, String name, String symbol, boolean enumerable, boolean soulbound) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_SYMBOL_LENGTH = 16;

    public Erc721Collection {
        Objects.requireNonNull(contract, "contract");
        //NFT metadata's rule (TextRules): one line, capped in code points
        name = TextRules.singleLine(name, MAX_NAME_LENGTH);
        symbol = TextRules.singleLine(symbol, MAX_SYMBOL_LENGTH);
    }
}
