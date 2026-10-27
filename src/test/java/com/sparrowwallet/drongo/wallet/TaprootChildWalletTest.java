package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class TaprootChildWalletTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private Coin previousCoin;

    @BeforeEach
    public void setUp() {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    private static Wallet seedWallet(ScriptType scriptType) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet(scriptType.name());
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(Keystore.fromSeed(seed, scriptType.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, scriptType, wallet.getKeystores(), 1));
        return wallet;
    }

    @Test
    public void taprootChildDerivesTheBip86Path() throws Exception {
        Wallet master = seedWallet(ScriptType.P2WPKH);
        Wallet child = master.addTaprootChildWallet();
        Wallet direct = seedWallet(ScriptType.P2TR);
        Assertions.assertEquals("Taproot", child.getName());
        Assertions.assertEquals(ScriptType.P2TR, child.getScriptType());
        Assertions.assertEquals("m/86'/2'/0'", child.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        Assertions.assertSame(master, child.getMasterWallet());
        Assertions.assertSame(child, master.getTaprootChildWallet());
        Assertions.assertEquals(direct.getFreshNode(KeyPurpose.RECEIVE).getAddress(), child.getFreshNode(KeyPurpose.RECEIVE).getAddress());
    }

    @Test
    public void taprootChildUsesTheMastersCoinWhateverIsFocused() throws Exception {
        Wallet master = seedWallet(ScriptType.P2WPKH);
        master.setCoin(Coin.LITECOIN);
        Coin focused = Coin.beginScope(Coin.DOGECOIN);
        try {
            Wallet child = master.addTaprootChildWallet();
            Assertions.assertEquals("m/86'/2'/0'", child.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
            Assertions.assertEquals(Coin.LITECOIN, child.getCoin());
        } finally {
            Coin.endScope(focused);
        }
    }

    @Test
    public void refusesWatchOnlyAndMwebAndDuplicates() throws Exception {
        Wallet master = seedWallet(ScriptType.P2WPKH);
        master.addTaprootChildWallet();
        Assertions.assertThrows(IllegalStateException.class, master::addTaprootChildWallet);

        Wallet taproot = seedWallet(ScriptType.P2TR);
        Assertions.assertThrows(IllegalStateException.class, taproot::addTaprootChildWallet, "a taproot wallet is its own taproot wallet");

        Wallet watchOnly = seedWallet(ScriptType.P2WPKH);
        Keystore keystore = watchOnly.getKeystores().getFirst();
        ExtendedKey xpub = keystore.getExtendedPublicKey();
        Keystore watch = new Keystore();
        watch.setSource(KeystoreSource.SW_WATCH);
        watch.setKeyDerivation(keystore.getKeyDerivation());
        watch.setExtendedPublicKey(xpub);
        watchOnly.getKeystores().clear();
        watchOnly.getKeystores().add(watch);
        Assertions.assertThrows(IllegalStateException.class, watchOnly::addTaprootChildWallet);
    }
}
