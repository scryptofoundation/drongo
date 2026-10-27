package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Base58;
import com.sparrowwallet.drongo.protocol.ScriptType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the keys of an ord-litecoin wallet from a Litecoin Core dumpwallet file.
 * Each key line is "WIF time flags # addr=...". Only keys the wallet has used are kept: unused keypool keys (reserve=1),
 * the HD seed itself and script lines are skipped. Private key bytes are held in arrays that clear() zeroes; they are
 * never written anywhere, and errors name the line number only, never the key text.
 */
public final class OrdWalletDump {
    private static final int LITECOIN_CORE_MAINNET_WIF = 0xB0;
    private static final int TEST_NETWORK_WIF = 0xEF;
    private static final Set<String> SKIPPED_FLAGS = Set.of("reserve=1", "hdseed=1", "inactivehdseed=1", "script=1");

    private OrdWalletDump() {}

    /** One private key from the dump. The secret stays in a byte array so it can be zeroed. */
    public static final class DumpedKey {
        private final byte[] secret;
        private final boolean compressed;
        private final byte[] publicKey;
        private boolean cleared;

        private DumpedKey(byte[] secret, boolean compressed) {
            this.secret = secret;
            this.compressed = compressed;
            this.publicKey = ECKey.fromPrivate(secret, compressed).getPubKey();
        }

        /** A key that can sign. The caller drops it as soon as signing is done. */
        public ECKey signingKey() {
            if(cleared) {
                throw new IllegalStateException("The dumped key has been cleared");
            }
            return ECKey.fromPrivate(secret, compressed);
        }

        public ECKey publicKey() {
            return ECKey.fromPublicOnly(publicKey);
        }

        public boolean isCompressed() {
            return compressed;
        }

        public boolean isCleared() {
            return cleared;
        }

        private void clear() {
            Arrays.fill(secret, (byte)0);
            cleared = true;
        }
    }

    public static List<DumpedKey> parse(Reader in, Network network) {
        List<DumpedKey> keys = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(in);
            String line;
            int lineNumber = 0;
            while((line = reader.readLine()) != null) {
                lineNumber++;
                String content = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
                String[] tokens = content.trim().split("\\s+");
                if(tokens.length == 0 || tokens[0].isEmpty()) {
                    continue;
                }
                if(Arrays.stream(tokens).anyMatch(SKIPPED_FLAGS::contains)) {
                    continue;
                }
                keys.add(decode(tokens[0], network, lineNumber));
            }
        } catch(IOException e) {
            clear(keys);
            throw new UncheckedIOException("Could not read the wallet dump", e);
        } catch(RuntimeException e) {
            clear(keys);
            throw e;
        }
        return keys;
    }

    private static DumpedKey decode(String wif, Network network, int lineNumber) {
        byte[] data;
        try {
            data = Base58.decodeChecked(wif);
        } catch(RuntimeException e) {
            throw new IllegalArgumentException("The private key on line " + lineNumber + " is not valid");
        }
        try {
            int version = data[0] & 0xff;
            if(!acceptedVersion(version, network)) {
                throw new IllegalArgumentException("The private key on line " + lineNumber + " is for a different network than " + network);
            }
            byte[] secret;
            boolean compressed;
            if(data.length == 34 && data[33] == 1) {
                secret = Arrays.copyOfRange(data, 1, 33);
                compressed = true;
            } else if(data.length == 33) {
                secret = Arrays.copyOfRange(data, 1, 33);
                compressed = false;
            } else {
                throw new IllegalArgumentException("The private key on line " + lineNumber + " has the wrong length");
            }
            try {
                return new DumpedKey(secret, compressed);
            } catch(RuntimeException e) {
                Arrays.fill(secret, (byte)0);
                throw new IllegalArgumentException("The private key on line " + lineNumber + " is not a valid key");
            }
        } finally {
            Arrays.fill(data, (byte)0);
        }
    }

    private static boolean acceptedVersion(int version, Network network) {
        if(version == network.getDumpedPrivateKeyHeader()) {
            return true;
        }
        return network == Network.MAINNET ? version == LITECOIN_CORE_MAINNET_WIF : version == TEST_NETWORK_WIF;
    }

    /**
     * Every address the key can receive at, by the script type that spends it: segwit, taproot key path, nested segwit and legacy.
     * Uncompressed keys are legacy only. Keyed by script type because a nested segwit address only reports itself as P2SH.
     */
    public static Map<ScriptType, Address> addresses(DumpedKey key) {
        ECKey publicKey = key.publicKey();
        List<ScriptType> types = key.isCompressed() ? List.of(ScriptType.P2WPKH, ScriptType.P2TR, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH) : List.of(ScriptType.P2PKH);
        Map<ScriptType, Address> addresses = new LinkedHashMap<>();
        for(ScriptType type : types) {
            addresses.put(type, type.getAddress(publicKey));
        }
        return addresses;
    }

    public static void clear(List<DumpedKey> keys) {
        for(DumpedKey key : keys) {
            key.clear();
        }
    }
}
