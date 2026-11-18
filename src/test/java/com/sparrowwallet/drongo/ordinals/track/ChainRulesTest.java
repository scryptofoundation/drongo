package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

public class ChainRulesTest {
    @Test
    public void firstInscriptionHeights() {
        Assertions.assertEquals(2_424_429, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.MAINNET));
        Assertions.assertEquals(2_669_127, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.TESTNET));
        Assertions.assertEquals(112_402, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.SIGNET));
        Assertions.assertEquals(0, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.REGTEST));
        Assertions.assertEquals(4_600_000, ChainRules.firstInscriptionHeight(Coin.DOGECOIN, Network.MAINNET));
        Assertions.assertEquals(186_920, ChainRules.firstInscriptionHeight(Coin.PEPECOIN, Network.MAINNET));
        Assertions.assertEquals(22_470, ChainRules.firstInscriptionHeight(Coin.BELLSCOIN, Network.MAINNET));
        Assertions.assertEquals(273_000, ChainRules.firstInscriptionHeight(Coin.JUNKCOIN, Network.MAINNET));
        for(Coin coin : new Coin[] {Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN}) {
            for(Network network : new Network[] {Network.TESTNET, Network.REGTEST, Network.SIGNET}) {
                Assertions.assertEquals(0, ChainRules.firstInscriptionHeight(coin, network), coin + " " + network);
            }
        }
    }

    @Test
    public void litecoinSubsidy() {
        Assertions.assertEquals(OptionalLong.of(5_000_000_000L), ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 0));
        Assertions.assertEquals(OptionalLong.of(2_500_000_000L), ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 840_000));
        Assertions.assertEquals(OptionalLong.of(1_250_000_000L), ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 2_500_000));
        Assertions.assertEquals(OptionalLong.of(1_250_000_000L), ChainRules.subsidy(Coin.LITECOIN, Network.REGTEST, 300));
        Assertions.assertEquals(OptionalLong.of(2_500_000_000L), ChainRules.subsidy(Coin.LITECOIN, Network.TESTNET, 840_000));
        Assertions.assertEquals(OptionalLong.of(0), ChainRules.subsidy(Coin.LITECOIN, Network.REGTEST, 150 * 64));
    }

    @Test
    public void dogecoinSubsidy() {
        Assertions.assertEquals(OptionalLong.of(1_000_000_000_000L), ChainRules.subsidy(Coin.DOGECOIN, Network.MAINNET, 4_600_000));
        Assertions.assertEquals(OptionalLong.empty(), ChainRules.subsidy(Coin.DOGECOIN, Network.MAINNET, 500_000));
        Assertions.assertEquals(OptionalLong.empty(), ChainRules.subsidy(Coin.DOGECOIN, Network.REGTEST, 10));
        Assertions.assertEquals(OptionalLong.empty(), ChainRules.subsidy(Coin.DOGECOIN, Network.TESTNET, 700_000));
    }

    @Test
    public void otherCoinsHaveNoCodedSubsidy() {
        for(Coin coin : new Coin[] {Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN}) {
            for(Network network : Network.values()) {
                Assertions.assertEquals(OptionalLong.empty(), ChainRules.subsidy(coin, network, 300_000), coin + " " + network);
            }
        }
    }

    @Test
    public void covers() {
        for(Coin coin : new Coin[] {Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN}) {
            Assertions.assertTrue(ChainRules.covers(coin), coin.toString());
        }
        Assertions.assertFalse(ChainRules.covers(Coin.LUCKYCOIN));
        Assertions.assertFalse(ChainRules.covers(Coin.DINGOCOIN));
    }
}
