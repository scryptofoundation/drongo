package com.sparrowwallet.drongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins which chains share a base58 address encoding, so the send path can warn instead of guessing.
 *
 * These chains were forked from one another and several kept the version bytes they inherited. Where
 * two share one, an address string is valid on both and carries nothing that says which was meant -
 * so an address pasted into the wrong wallet is accepted, broadcast, and the funds are gone. The
 * defence cannot be better parsing, because there is nothing left to parse; it can only be the
 * sending wallet's own coin, plus telling the user when the address does not pin the chain down.
 *
 * The collisions are over the version byte alone, not over the script type. Dogecoin's P2PKH header
 * and Bellscoin's P2SH header are both 30, so a Bellscoin P2SH address is a well-formed Dogecoin
 * P2PKH address for a different hash160 - comparing like-for-like script types would miss it.
 */
public class CoinAddressAmbiguityTest {
    @AfterEach
    public void resetCoin() {
        Coin.set(Coin.LITECOIN);
    }

    @Test
    public void dogecoinP2pkhIsAlsoValidOnDingocoinAndBellscoin() {
        //30 is Dogecoin P2PKH, Dingocoin P2PKH (identical chain identity) and Bellscoin P2SH.
        Assertions.assertEquals(List.of(Coin.BELLSCOIN, Coin.DINGOCOIN),
                Coin.coinsSharingAddressEncoding(Coin.DOGECOIN, 30, Network.MAINNET));
    }

    @Test
    public void dingocoinP2pkhIsAlsoValidOnDogecoin() {
        Assertions.assertEquals(List.of(Coin.DOGECOIN, Coin.BELLSCOIN),
                Coin.coinsSharingAddressEncoding(Coin.DINGOCOIN, 30, Network.MAINNET));
    }

    @Test
    public void dogecoinP2shIsAlsoValidOnPepecoinAndDingocoin() {
        Assertions.assertEquals(List.of(Coin.PEPECOIN, Coin.DINGOCOIN),
                Coin.coinsSharingAddressEncoding(Coin.DOGECOIN, 22, Network.MAINNET));
    }

    @Test
    public void luckycoinP2shIsAlsoValidOnJunkcoin() {
        //Both inherited Bitcoin's P2SH version byte 5, so these are Bitcoin "3..." addresses too.
        Assertions.assertEquals(List.of(Coin.JUNKCOIN),
                Coin.coinsSharingAddressEncoding(Coin.LUCKYCOIN, 5, Network.MAINNET));
    }

    @Test
    public void litecoinAddressesArePinnedToLitecoin() {
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.LITECOIN, 48, Network.MAINNET),
                "Litecoin P2PKH 48 is not shared by any other supported coin");
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.LITECOIN, 50, Network.MAINNET),
                "Litecoin P2SH 50 is not shared by any other supported coin");
    }

    @Test
    public void unsharedVersionBytesReportNoAmbiguity() {
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.PEPECOIN, 56, Network.MAINNET));
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.LUCKYCOIN, 47, Network.MAINNET));
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.BELLSCOIN, 25, Network.MAINNET));
        Assertions.assertEquals(List.of(),
                Coin.coinsSharingAddressEncoding(Coin.JUNKCOIN, 16, Network.MAINNET));
    }

    @Test
    public void aCoinIsNeverAmbiguousWithItself() {
        for(Coin coin : Coin.values()) {
            Coin.ChainParams params = coin.params(Network.MAINNET);

            Assertions.assertFalse(
                    Coin.coinsSharingAddressEncoding(coin, params.p2pkhAddressHeader(), Network.MAINNET).contains(coin),
                    coin + " listed itself as sharing its own P2PKH encoding");
            Assertions.assertFalse(
                    Coin.coinsSharingAddressEncoding(coin, params.p2shAddressHeader(), Network.MAINNET).contains(coin),
                    coin + " listed itself as sharing its own P2SH encoding");
        }
    }

    /**
     * Sharing an encoding is mutual. If A's address is valid on B then B must report A, or a warning
     * would appear on one chain's send form and not the other's.
     */
    @Test
    public void sharedEncodingsAreSymmetric() {
        List<String> asymmetric = new ArrayList<>();

        for(Coin coin : Coin.values()) {
            Coin.ChainParams params = coin.params(Network.MAINNET);

            for(int versionByte : new int[]{params.p2pkhAddressHeader(), params.p2shAddressHeader()}) {
                for(Coin other : Coin.coinsSharingAddressEncoding(coin, versionByte, Network.MAINNET)) {
                    if(!Coin.coinsSharingAddressEncoding(other, versionByte, Network.MAINNET).contains(coin)) {
                        asymmetric.add(coin + " -> " + other + " at version byte " + versionByte);
                    }
                }
            }
        }

        Assertions.assertEquals(List.of(), asymmetric, "sharing an address encoding must be mutual");
    }
}
