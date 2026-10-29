package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.*;
import com.sparrowwallet.drongo.evm.EvmAccount;
import com.sparrowwallet.drongo.evm.EvmAddress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A LitVM account linked to a Litecoin master wallet, shown as a LitVM sub-tab under it.
 *
 * WALLET_SEED derives the account from the wallet's own seed at m/44'/60'/0'/0/accountIndex and stores no
 * secret. MNEMONIC and PRIVATE_KEY hold a separate account's secret (BIP39 words without passphrase, as
 * MetaMask uses, or a raw 32-byte key) that is encrypted with the wallet's key exactly like a keystore seed:
 * Wallet.encrypt/decrypt/clearPrivate/copy all cover it.
 *
 * The address is stored in the clear so balance and receiving work without the wallet password; the secret
 * is only needed to sign.
 */
public class LitVmLink extends Persistable implements EncryptableItem {
    public enum Source {
        WALLET_SEED("This wallet's seed"), MNEMONIC("Separate seed words"), PRIVATE_KEY("Separate private key");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final Source source;
    private final EvmAddress address;
    private final int accountIndex;
    private final byte[] secret;
    private final EncryptedData encryptedSecret;
    private final long creationTimeSeconds;

    private LitVmLink(Source source, EvmAddress address, int accountIndex, byte[] secret, EncryptedData encryptedSecret, long creationTimeSeconds) {
        if(source != Source.WALLET_SEED && secret == null && encryptedSecret == null) {
            throw new IllegalArgumentException("A separate LitVM account needs its secret");
        }
        this.source = source;
        this.address = address;
        this.accountIndex = accountIndex;
        this.secret = secret;
        this.encryptedSecret = encryptedSecret;
        this.creationTimeSeconds = creationTimeSeconds;
    }

    /** Links the account derived from the wallet's own seed. The caller derives it from the (decrypted) seed. */
    public static LitVmLink fromWalletSeed(DeterministicSeed decryptedSeed, int accountIndex) throws MnemonicException {
        EvmAccount account = EvmAccount.fromSeed(decryptedSeed, accountIndex);
        return new LitVmLink(Source.WALLET_SEED, account.getAddress(), accountIndex, null, null, System.currentTimeMillis() / 1000);
    }

    /** Links a separate account from BIP39 words (no passphrase), at m/44'/60'/0'/0/accountIndex. */
    public static LitVmLink fromMnemonic(CharSequence words, int accountIndex) throws MnemonicException {
        List<String> mnemonic = new ArrayList<>(Arrays.asList(words.toString().trim().toLowerCase(Locale.ROOT).split("\\s+")));
        Bip39MnemonicCode.INSTANCE.check(mnemonic);
        byte[] secret = SecureString.toBytesUTF8(String.join(" ", mnemonic));
        DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
        try {
            EvmAccount account = EvmAccount.fromSeed(seed, accountIndex);
            return new LitVmLink(Source.MNEMONIC, account.getAddress(), accountIndex, secret, null, System.currentTimeMillis() / 1000);
        } finally {
            seed.clear();
        }
    }

    /** Links a separate account from a raw private key, hex with or without 0x. */
    public static LitVmLink fromPrivateKey(String hex) {
        String clean = hex.trim();
        if(clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if(!clean.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("A private key is 64 hex characters");
        }
        byte[] secret = com.sparrowwallet.drongo.Utils.hexToBytes(clean);
        ECKey key = ECKey.fromPrivate(secret);
        EvmAccount account = EvmAccount.fromPrivateKey(key);
        return new LitVmLink(Source.PRIVATE_KEY, account.getAddress(), 0, secret, null, System.currentTimeMillis() / 1000);
    }

    /** Rebuilds a stored link; exactly one of secret and encryptedSecret is set for a separate account. */
    public static LitVmLink restore(Source source, EvmAddress address, int accountIndex, byte[] secret, EncryptedData encryptedSecret, long creationTimeSeconds) {
        return new LitVmLink(source, address, accountIndex, secret, encryptedSecret, creationTimeSeconds);
    }

    public Source getSource() {
        return source;
    }

    public EvmAddress getAddress() {
        return address;
    }

    public int getAccountIndex() {
        return accountIndex;
    }

    public boolean isSeparateAccount() {
        return source != Source.WALLET_SEED;
    }

    public String getDerivationPath() {
        return source == Source.PRIVATE_KEY ? null : EvmAccount.DERIVATION_PREFIX + accountIndex;
    }

    /**
     * The signing account. For WALLET_SEED pass the wallet's decrypted seed; for a separate account this link
     * must be decrypted first. The result holds the private key, so drop it as soon as the signature is made.
     */
    public EvmAccount getAccount(DeterministicSeed decryptedWalletSeed) throws MnemonicException {
        EvmAccount account = switch(source) {
            case WALLET_SEED -> {
                if(decryptedWalletSeed == null || decryptedWalletSeed.isEncrypted()) {
                    throw new IllegalStateException("The wallet seed must be decrypted to sign");
                }
                yield EvmAccount.fromSeed(decryptedWalletSeed, accountIndex);
            }
            case MNEMONIC -> {
                requireDecrypted();
                DeterministicSeed seed = new DeterministicSeed(new String(secret, StandardCharsets.UTF_8), "", 0, DeterministicSeed.Type.BIP39);
                try {
                    yield EvmAccount.fromSeed(seed, accountIndex);
                } finally {
                    seed.clear();
                }
            }
            case PRIVATE_KEY -> {
                requireDecrypted();
                yield EvmAccount.fromPrivateKey(ECKey.fromPrivate(secret));
            }
        };

        if(!account.getAddress().equals(address)) {
            throw new IllegalStateException("Derived LitVM address " + account.getAddress() + " does not match the linked address " + address);
        }
        return account;
    }

    private void requireDecrypted() {
        if(secret == null) {
            throw new IllegalStateException("The LitVM account must be decrypted to sign");
        }
    }

    @Override
    public boolean isEncrypted() {
        return encryptedSecret != null;
    }

    @Override
    public byte[] getSecretBytes() {
        return secret;
    }

    @Override
    public EncryptedData getEncryptedData() {
        return encryptedSecret;
    }

    @Override
    public EncryptionType getEncryptionType() {
        return new EncryptionType(EncryptionType.Deriver.ARGON2, EncryptionType.Crypter.AES_CBC_PKCS7);
    }

    @Override
    public long getCreationTimeMillis() {
        return creationTimeSeconds * 1000;
    }

    public long getCreationTimeSeconds() {
        return creationTimeSeconds;
    }

    public LitVmLink encrypt(Key key) {
        if(!isSeparateAccount() || isEncrypted()) {
            return this;
        }

        KeyCrypter keyCrypter = getEncryptionType().getCrypter().getKeyCrypter();
        EncryptedData encrypted = keyCrypter.encrypt(secret, null, key);
        LitVmLink link = new LitVmLink(source, address, accountIndex, null, encrypted, creationTimeSeconds);
        link.setId(getId());
        return link;
    }

    public LitVmLink decrypt(CharSequence password) {
        if(!isEncrypted()) {
            return this;
        }

        KeyDeriver keyDeriver = getEncryptionType().getDeriver().getKeyDeriver(encryptedSecret.getKeySalt());
        Key key = keyDeriver.deriveKey(password);
        try {
            return decrypt(key);
        } finally {
            key.clear();
        }
    }

    public LitVmLink decrypt(Key key) {
        if(!isEncrypted()) {
            return this;
        }

        KeyCrypter keyCrypter = getEncryptionType().getCrypter().getKeyCrypter();
        byte[] decrypted = keyCrypter.decrypt(encryptedSecret, key);
        LitVmLink link = new LitVmLink(source, address, accountIndex, decrypted, null, creationTimeSeconds);
        link.setId(getId());
        return link;
    }

    /** Wipes the plaintext secret in place. The link is unusable for signing afterwards. */
    public void clear() {
        if(secret != null) {
            Arrays.fill(secret, (byte)0);
        }
    }

    public LitVmLink copy() {
        LitVmLink link = new LitVmLink(source, address, accountIndex, secret == null ? null : Arrays.copyOf(secret, secret.length), encryptedSecret, creationTimeSeconds);
        link.setId(getId());
        return link;
    }
}
