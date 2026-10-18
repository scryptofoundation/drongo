package com.sparrowwallet.drongo.policy;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.protocol.ScriptType;

import static com.sparrowwallet.drongo.protocol.ScriptType.*;

public enum PolicyType {
    SINGLE("Single Signature", P2WPKH), MULTI("Multi Signature", P2WSH), CUSTOM("Custom", P2WSH);

    private String name;
    private ScriptType defaultScriptType;

    PolicyType(String name, ScriptType defaultScriptType) {
        this.name = name;
        this.defaultScriptType = defaultScriptType;
    }

    public String getName() {
        return name;
    }

    public ScriptType getDefaultScriptType() {
        //The segwit defaults (P2WPKH/P2WSH) are invalid on chains without segwit - every merge-mined
        //Scrypto chain except Litecoin. Fall back to the legacy equivalent: single-sig -> P2PKH, multisig -> P2SH.
        if(!Coin.get().supports(defaultScriptType)) {
            return this == SINGLE ? P2PKH : P2SH;
        }

        return defaultScriptType;
    }

    @Override
    public String toString() {
        return name;
    }
}
