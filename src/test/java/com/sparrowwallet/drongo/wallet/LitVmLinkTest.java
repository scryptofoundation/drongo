package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.crypto.KeyDeriver;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LitVmLinkTest {
    //Hardhat/Anvil default account 0: these words give this address and key in MetaMask
    private static final String WORDS = "test test test test test test test test test test test junk";
    private static final String PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final EvmAddress ADDRESS = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    @Test
    public void separateAccountsMatchMetaMask() throws MnemonicException {
        Assertions.assertEquals(ADDRESS, LitVmLink.fromMnemonic(WORDS, 0).getAddress());
        Assertions.assertEquals(ADDRESS, LitVmLink.fromPrivateKey(PRIVATE_KEY).getAddress());
        Assertions.assertEquals(ADDRESS, LitVmLink.fromPrivateKey(PRIVATE_KEY.substring(2)).getAddress());
        Assertions.assertThrows(MnemonicException.class, () -> LitVmLink.fromMnemonic("test test test", 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmLink.fromPrivateKey("0x1234"));
    }

    @Test
    public void walletSeedLinkStoresNoSecret() throws MnemonicException {
        DeterministicSeed seed = new DeterministicSeed(WORDS, "", 0, DeterministicSeed.Type.BIP39);
        LitVmLink link = LitVmLink.fromWalletSeed(seed, 0);
        Assertions.assertEquals(ADDRESS, link.getAddress());
        Assertions.assertNull(link.getSecretBytes());
        Assertions.assertFalse(link.isSeparateAccount());
        Assertions.assertEquals(ADDRESS, link.getAccount(seed).getAddress());
    }

    @Test
    public void walletEncryptionCoversSeparateAccount() throws MnemonicException {
        for(LitVmLink link : new LitVmLink[] {LitVmLink.fromMnemonic(WORDS, 0), LitVmLink.fromPrivateKey(PRIVATE_KEY)}) {
            Wallet wallet = new Wallet();
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(ScriptType.P2WPKH);
            DeterministicSeed walletSeed = new DeterministicSeed("absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(walletSeed, PolicyType.SINGLE_HD, wallet.getScriptType().getDefaultDerivation()));
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            wallet.setLitVmLink(link);

            KeyDeriver keyDeriver = new Argon2KeyDeriver();
            Key key = keyDeriver.deriveKey("pass");
            wallet.encrypt(key);

            Assertions.assertTrue(wallet.isEncrypted());
            Assertions.assertTrue(wallet.getLitVmLink().isEncrypted());
            Assertions.assertNull(wallet.getLitVmLink().getSecretBytes());
            Assertions.assertEquals(ADDRESS, wallet.getLitVmLink().getAddress());
            Assertions.assertThrows(IllegalStateException.class, () -> wallet.getLitVmLink().getAccount(null));

            //A copy carries the encrypted secret, as DecryptWalletService relies on
            Wallet copy = wallet.copy();
            copy.decrypt("pass");
            Assertions.assertFalse(copy.getLitVmLink().isEncrypted());
            Assertions.assertEquals(ADDRESS, copy.getLitVmLink().getAccount(null).getAddress());
            Assertions.assertTrue(wallet.getLitVmLink().isEncrypted());

            copy.clearPrivate();
            Assertions.assertTrue(java.util.Arrays.equals(new byte[copy.getLitVmLink().getSecretBytes().length], copy.getLitVmLink().getSecretBytes()));
            Assertions.assertThrows(Exception.class, () -> copy.getLitVmLink().getAccount(null));
        }
    }
}
