package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class KnownTokensTest {
    private static final EvmAddress OTHER = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");

    private static Erc20Token token(EvmAddress contract, String symbol, String name) {
        return new Erc20Token(contract, symbol, name, 18);
    }

    @Test
    public void officialContractsAreKnownOnTheirOwnNetworkOnly() {
        Erc20Token tusdc = UniswapV2Venue.TUSDC;
        Assertions.assertTrue(KnownTokens.official(LitVmNetwork.LITEFORGE_TESTNET, tusdc.contract()).isPresent());
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, tusdc).isEmpty());
        Assertions.assertTrue(KnownTokens.official(LitVmNetwork.BASE_SEPOLIA, tusdc.contract()).isEmpty());
        Assertions.assertTrue(KnownTokens.official(LitVmNetwork.BASE_SEPOLIA, UniswapV3Venue.USDC.contract()).isPresent());
        Assertions.assertTrue(KnownTokens.official(LitVmNetwork.SEPOLIA, ZkLtcBridge.ZKLTC_L1.contract()).isPresent());
        Assertions.assertTrue(KnownTokens.official(LitVmNetwork.LITEFORGE_TESTNET, JunkcoinBridge.WJKC.contract()).isPresent());
    }

    @Test
    public void aKnownNameOnAnotherContractIsALookalike() {
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.BASE_SEPOLIA, token(OTHER, "USDC", "USD Coin")).orElseThrow().contains("USDC"));
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, token(OTHER, "usd-c", "Dollar")).isPresent());
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.SEPOLIA, token(OTHER, "SPTT", "Tether USD")).isPresent());
        //zkLTC is LiteForge's native coin, so any token by that name there is pretending
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, token(OTHER, "zkLTC", "zkLTC")).isPresent());
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, token(OTHER, "LTC", "Litecoin")).isPresent());
    }

    @Test
    public void nonLatinLettersAreALookalike() {
        //Cyrillic Es in place of the final C
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.BASE_SEPOLIA, token(OTHER, "USDС", "Coin")).isPresent());
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.BASE_SEPOLIA, token(OTHER, "MINE", "Mіne")).isPresent());
    }

    @Test
    public void anOrdinaryNameIsNot() {
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, token(OTHER, "SPTT", "Sparrow Test Token")).isEmpty());
        Assertions.assertTrue(KnownTokens.lookalike(LitVmNetwork.LITEFORGE_TESTNET, token(OTHER, "CATS", "Drunken Cats")).isEmpty());
    }

    @Test
    public void creatingAKnownNameIsRefused() {
        Assertions.assertTrue(KnownTokens.imitates("USDC", "My Token").isPresent());
        Assertions.assertTrue(KnownTokens.imitates("MYT", "Wrapped Ether").isPresent());
        Assertions.assertTrue(KnownTokens.imitates("MYT", "My Token").isEmpty());
    }
}
