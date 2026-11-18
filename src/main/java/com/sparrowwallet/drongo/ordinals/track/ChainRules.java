package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;

import java.util.OptionalLong;
import java.util.Set;

/** Per coin facts the tracer relies on: where inscriptions begin, and block rewards where they are exact. */
public final class ChainRules {
    private static final long COIN = 100_000_000L;
    private static final Set<Coin> COVERED = Set.of(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN);

    private ChainRules() {
    }

    /** The coins the built-in tracker checks. */
    public static boolean covers(Coin coin) {
        return COVERED.contains(coin);
    }

    /**
     * The height of the chain's first inscription, below which no unit can carry one. From each indexer's chain.rs
     * (ord-litecoin, ord-dogecoin, ord-pepecoin, ord-bells, ord-junkcoin); 0 where the indexer has none.
     */
    public static int firstInscriptionHeight(Coin coin, Network network) {
        return switch(coin) {
            case LITECOIN -> switch(network) {
                case MAINNET -> 2_424_429;
                case TESTNET -> 2_669_127;
                case SIGNET -> 112_402;
                default -> 0;
            };
            case DOGECOIN -> network == Network.MAINNET ? 4_600_000 : 0;
            case PEPECOIN -> network == Network.MAINNET ? 186_920 : 0;
            case BELLSCOIN -> network == Network.MAINNET ? 22_470 : 0;
            case JUNKCOIN -> network == Network.MAINNET ? 273_000 : 0;
            default -> 0;
        };
    }

    /**
     * The block reward at a height, only where it is exact and simple: Litecoin's halving schedule, and Dogecoin
     * mainnet's flat reward from height 600,000. Empty elsewhere (random or custom schedules).
     */
    public static OptionalLong subsidy(Coin coin, Network network, int height) {
        if(coin == Coin.LITECOIN) {
            int interval = network == Network.REGTEST ? 150 : 840_000;
            int halvings = height / interval;
            return OptionalLong.of(halvings >= 64 ? 0 : (50 * COIN) >> halvings);
        }
        if(coin == Coin.DOGECOIN && network == Network.MAINNET && height >= 600_000) {
            return OptionalLong.of(10_000 * COIN);
        }
        return OptionalLong.empty();
    }
}
