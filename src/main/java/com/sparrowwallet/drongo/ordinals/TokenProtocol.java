package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;

import java.util.Locale;
import java.util.Optional;

/**
 * The brc-20 style JSON token protocols carried in text inscriptions, one per coin.
 */
public enum TokenProtocol {
    LTC20("ltc-20", Coin.LITECOIN),
    DRC20("drc-20", Coin.DOGECOIN),
    PRC20("prc-20", Coin.PEPECOIN),
    BEL20("bel-20", Coin.BELLSCOIN),
    JUNK20("junk-20", Coin.JUNKCOIN);

    private final String p;
    private final Coin coin;

    TokenProtocol(String p, Coin coin) {
        this.p = p;
        this.coin = coin;
    }

    public String p() {
        return p;
    }

    public Coin coin() {
        return coin;
    }

    public String displayName() {
        return p.toUpperCase(Locale.ROOT);
    }

    public static Optional<TokenProtocol> forCoin(Coin coin) {
        for(TokenProtocol protocol : values()) {
            if(protocol.coin == coin) {
                return Optional.of(protocol);
            }
        }

        return Optional.empty();
    }
}
