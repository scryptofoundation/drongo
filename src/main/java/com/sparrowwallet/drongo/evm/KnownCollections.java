package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The NFT collections the LitVM tab knows, each by its address on one network. Anyone can deploy a collection with
 * any name, so only the address identifies a known collection; one that takes a known name or symbol at another
 * address is a lookalike.
 *
 * A known collection may carry its own rule for finding a holder's tokens without an explorer, such as the Vault
 * Badge's token ids computed from the holder's address.
 */
public final class KnownCollections {
    private KnownCollections() {}

    /** The token ids a holder may have in a collection, and how many more candidates were not looked up. */
    record Candidates(List<BigInteger> tokenIds, BigInteger notLookedUp) {}

    /** How a known collection's candidate token ids for a holder are found. Throws on a collection-level failure. */
    @FunctionalInterface
    interface TokenIdRule {
        Candidates candidates(LitVmRpcClient client, EvmAddress contract, EvmAddress owner) throws IOException;
    }

    record Known(LitVmNetwork network, EvmAddress contract, String name, String symbol, TokenIdRule tokenIds) {}

    /** The Super Litecoin Vault Badge: deployed on LiteForge (super_litecoin contracts/deployments/litvm_testnet.json). */
    static final EvmAddress VAULT_BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    //The badge has one token per world; its worlds() is the contract's answer, so only this many are looked up
    static final int MAX_VAULT_BADGE_WORLDS = 64;

    private static final List<Known> KNOWN = List.of(
            new Known(LitVmNetwork.LITEFORGE_TESTNET, VAULT_BADGE, "Super Litecoin Vault Badge", "SLVB", KnownCollections::vaultBadgeTokenIds));

    /** The known collections on a network. */
    public static List<EvmAddress> on(LitVmNetwork network) {
        return KNOWN.stream().filter(k -> k.network() == network).map(Known::contract).toList();
    }

    /** Whether this address is a known collection on this network: by address only, never by name. */
    public static boolean isKnown(LitVmNetwork network, EvmAddress contract) {
        return known(network, contract).isPresent();
    }

    static Optional<Known> known(LitVmNetwork network, EvmAddress contract) {
        return KNOWN.stream().filter(k -> k.network() == network && k.contract().equals(contract)).findFirst();
    }

    /**
     * The name of the known collection this one imitates, as the wallet names it (the wallet's own text, never the
     * contract's), or empty when it imitates none or is itself known. A collection that is not a known one imitates a
     * known one when it takes its name or symbol, compared after cleaning (control and format characters removed),
     * case folding and dropping everything but letters and digits.
     */
    public static Optional<String> lookalikeOf(LitVmNetwork network, Erc721Collection collection) {
        if(isKnown(network, collection.contract())) {
            return Optional.empty();
        }
        String name = normalise(collection.name());
        String symbol = normalise(collection.symbol());
        for(Known known : KNOWN) {
            String knownName = normalise(known.name());
            String knownSymbol = normalise(known.symbol());
            if((!name.isEmpty() && (name.equals(knownName) || name.equals(knownSymbol)))
                    || (!symbol.isEmpty() && (symbol.equals(knownSymbol) || symbol.equals(knownName)))) {
                return Optional.of(known.name());
            }
        }
        return Optional.empty();
    }

    private static String normalise(String text) {
        String folded = TextRules.singleLine(text, Integer.MAX_VALUE).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder();
        folded.codePoints().filter(Character::isLetterOrDigit).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    /** The badge's token id for a world: (uint160(owner) << 8) | world, as VaultBadge.tokenIdOf computes it. */
    static BigInteger vaultBadgeTokenId(EvmAddress owner, int world) {
        return new BigInteger(1, owner.getBytes()).shiftLeft(8).or(BigInteger.valueOf(world));
    }

    private static Candidates vaultBadgeTokenIds(LitVmRpcClient client, EvmAddress contract, EvmAddress owner) throws IOException {
        BigInteger worlds;
        try {
            worlds = Abi.decodeUint(client.ethCall(contract, Abi.selector("worlds()"), 32 * 4), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid worlds() result from " + contract);
        }
        int lookedUp = worlds.min(BigInteger.valueOf(MAX_VAULT_BADGE_WORLDS)).intValueExact();
        List<BigInteger> ids = new ArrayList<>();
        for(int world = 1; world <= lookedUp; world++) {
            ids.add(vaultBadgeTokenId(owner, world));
        }
        return new Candidates(ids, worlds.subtract(BigInteger.valueOf(lookedUp)));
    }
}
