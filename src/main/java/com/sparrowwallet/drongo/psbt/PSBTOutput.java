package com.sparrowwallet.drongo.psbt;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.MwebAddress;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.SchnorrSignature;
import com.sparrowwallet.drongo.dns.DnsPayment;
import com.sparrowwallet.drongo.dns.DnsPaymentResolver;
import com.sparrowwallet.drongo.dns.DnsPaymentValidationException;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentAddress;
import com.sparrowwallet.drongo.uri.BitcoinURIParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.sparrowwallet.drongo.protocol.ScriptType.*;
import static com.sparrowwallet.drongo.psbt.PSBTEntry.*;

public class PSBTOutput {
    public static final byte PSBT_OUT_REDEEM_SCRIPT = 0x00;
    public static final byte PSBT_OUT_WITNESS_SCRIPT = 0x01;
    public static final byte PSBT_OUT_BIP32_DERIVATION = 0x02;
    public static final byte PSBT_OUT_AMOUNT = 0x03;
    public static final byte PSBT_OUT_SCRIPT = 0x04;
    public static final byte PSBT_OUT_TAP_INTERNAL_KEY = 0x05;
    public static final byte PSBT_OUT_TAP_BIP32_DERIVATION = 0x07;
    public static final byte PSBT_OUT_SP_V0_INFO = 0x09;
    public static final byte PSBT_OUT_SP_V0_LABEL = 0x0a;
    public static final byte PSBT_OUT_DNSSEC_PROOF = 0x35;
    public static final byte PSBT_OUT_MWEB_STEALTH_ADDRESS = (byte)0x90;
    public static final byte PSBT_OUT_MWEB_OUTPUT_COMMIT = (byte)0x91;
    public static final byte PSBT_OUT_MWEB_FEATURES = (byte)0x92;
    public static final byte PSBT_OUT_MWEB_SENDER_PUBKEY = (byte)0x93;
    public static final byte PSBT_OUT_MWEB_OUTPUT_PUBKEY = (byte)0x94;
    public static final byte PSBT_OUT_MWEB_STANDARD_FIELDS = (byte)0x95;
    public static final byte PSBT_OUT_MWEB_RANGE_PROOF = (byte)0x96;
    public static final byte PSBT_OUT_MWEB_SIGNATURE = (byte)0x97;
    public static final byte PSBT_OUT_MWEB_EXTRA_DATA = (byte)0x98;
    public static final byte PSBT_OUT_PROPRIETARY = (byte)0xfc;

    private Script redeemScript;
    private Script witnessScript;
    private final Map<ECKey, KeyDerivation> derivedPublicKeys = new LinkedHashMap<>();
    private final Map<String, String> proprietary = new LinkedHashMap<>();
    private Map<ECKey, Map<KeyDerivation, List<Sha256Hash>>> tapDerivedPublicKeys = new LinkedHashMap<>();
    private ECKey tapInternalKey;
    private Map<String, byte[]> dnssecProof;

    //PSBTv2-only fields
    private Long amount;
    private Script script;
    private SilentPaymentAddress silentPaymentAddress;
    private Long silentPaymentLabel;
    private MwebAddress mwebStealthAddress;
    private byte[] mwebOutputCommit;
    private Byte mwebFeatures;
    private ECKey mwebSenderPubKey;
    private ECKey mwebOutputPubKey;
    private ECKey mwebKeyExchangePubKey;
    private Byte mwebViewTag;
    private Long mwebEncryptedValue;
    private byte[] mwebEncryptedNonce;
    private byte[] mwebRangeProof;
    private SchnorrSignature mwebSignature;
    private byte[] mwebExtraData;

    private static final Logger log = LoggerFactory.getLogger(PSBTOutput.class);

    private final PSBT psbt;
    private int index;

    PSBTOutput(PSBT psbt, int index) {
        this.psbt = psbt;
        this.index = index;
    }

    PSBTOutput(PSBT psbt, int index, ScriptType scriptType, Long amount, Script script, Script redeemScript, Script witnessScript, Map<ECKey, KeyDerivation> derivedPublicKeys,
               Map<String, String> proprietary, ECKey tapInternalKey, SilentPaymentAddress silentPaymentAddress, Long silentPaymentLabel, Map<String, byte[]> dnssecProof) {
        this(psbt, index);

        this.redeemScript = redeemScript;
        this.witnessScript = witnessScript;

        if(scriptType != P2TR) {
            this.derivedPublicKeys.putAll(derivedPublicKeys);
        }

        this.proprietary.putAll(proprietary);

        this.tapInternalKey = tapInternalKey == null ? null : ECKey.fromPublicOnly(tapInternalKey.getPubKeyXCoord());

        if(tapInternalKey != null && !derivedPublicKeys.values().isEmpty()) {
            KeyDerivation tapKeyDerivation = derivedPublicKeys.values().iterator().next();
            tapDerivedPublicKeys.put(this.tapInternalKey, Map.of(tapKeyDerivation, Collections.emptyList()));
        }

        this.silentPaymentAddress = silentPaymentAddress;
        this.silentPaymentLabel = silentPaymentLabel;
        this.dnssecProof = dnssecProof;

        //Populate PSBTv2 fields if parent PSBT is v2
        if(psbt.getPsbtVersion() >= 2) {
            this.amount = amount;
            if(!script.isEmpty() || silentPaymentAddress == null) {
                this.script = script;
            }
        }
    }

    PSBTOutput(PSBT psbt, List<PSBTEntry> outputEntries, int index) throws PSBTParseException {
        this(psbt, index);
        for(PSBTEntry entry : outputEntries) {
            switch((byte)entry.getKeyType()) {
                case PSBT_OUT_REDEEM_SCRIPT:
                    entry.checkOneByteKey();
                    Script redeemScript = new Script(entry.getData());
                    this.redeemScript = redeemScript;
                    log.debug("Found output redeem script hex " + Utils.bytesToHex(redeemScript.getProgram()) + " script " + redeemScript);
                    break;
                case PSBT_OUT_WITNESS_SCRIPT:
                    entry.checkOneByteKey();
                    Script witnessScript = new Script(entry.getData());
                    this.witnessScript = witnessScript;
                    log.debug("Found output witness script hex " + Utils.bytesToHex(witnessScript.getProgram()) + " script " + witnessScript);
                    break;
                case PSBT_OUT_BIP32_DERIVATION:
                    entry.checkOneBytePlusPubKey();
                    ECKey derivedPublicKey = ECKey.fromPublicOnly(entry.getKeyData());
                    KeyDerivation keyDerivation = parseKeyDerivation(entry.getData());
                    this.derivedPublicKeys.put(derivedPublicKey, keyDerivation);
                    log.debug("Found output bip32_derivation with master fingerprint " + keyDerivation.getMasterFingerprint() + " at path " + keyDerivation.getDerivationPath() + " public key " + derivedPublicKey);
                    break;
                case PSBT_OUT_AMOUNT:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 8) {
                        throw new PSBTParseException("PSBT output amount must be 8 bytes");
                    }
                    long outputAmount = Utils.readInt64(entry.getData(), 0);
                    if(outputAmount < 0 || outputAmount > Transaction.MAX_SATOSHIS) {
                        throw new PSBTParseException("PSBT output amount is out of range: " + outputAmount);
                    }
                    this.amount = outputAmount;
                    log.debug("Found output amount " + this.amount);
                    break;
                case PSBT_OUT_SCRIPT:
                    entry.checkOneByteKey();
                    Script script = new Script(entry.getData());
                    this.script = script;
                    log.debug("Found output script hex " + Utils.bytesToHex(script.getProgram()) + " script " + script);
                    break;
                case PSBT_OUT_PROPRIETARY:
                    entry.checkOneBytePlusKeyData();
                    proprietary.put(Utils.bytesToHex(entry.getKeyData()), Utils.bytesToHex(entry.getData()));
                    log.debug("Found proprietary output " + Utils.bytesToHex(entry.getKeyData()) + ": " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_TAP_INTERNAL_KEY:
                    entry.checkOneByteKey();
                    this.tapInternalKey = ECKey.fromPublicOnly(entry.getData());
                    log.debug("Found output taproot internal key " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_TAP_BIP32_DERIVATION:
                    entry.checkOneBytePlusXOnlyPubKey();
                    ECKey tapPublicKey = ECKey.fromPublicOnly(entry.getKeyData());
                    Map<KeyDerivation, List<Sha256Hash>> tapKeyDerivations = parseTaprootKeyDerivation(entry.getData());
                    if(tapKeyDerivations.isEmpty()) {
                        log.warn("PSBT provided an invalid output taproot key derivation");
                    } else {
                        this.tapDerivedPublicKeys.put(tapPublicKey, tapKeyDerivations);
                        for(KeyDerivation tapKeyDerivation : tapKeyDerivations.keySet()) {
                            log.debug("Found output taproot key derivation for key " + Utils.bytesToHex(entry.getKeyData()) + " with master fingerprint " + tapKeyDerivation.getMasterFingerprint() + " at path " + tapKeyDerivation.getDerivationPath());
                        }
                    }
                    break;
                case PSBT_OUT_SP_V0_INFO:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 66) {
                        throw new PSBTParseException("PSBT output info data for silent payments address must contain 66 bytes");
                    }
                    byte[] scanKey = new byte[33];
                    System.arraycopy(entry.getData(), 0, scanKey, 0, 33);
                    byte[] spendKey = new byte[33];
                    System.arraycopy(entry.getData(), 33, spendKey, 0, 33);
                    this.silentPaymentAddress = new SilentPaymentAddress(ECKey.fromPublicOnly(scanKey), ECKey.fromPublicOnly(spendKey));
                    log.debug("Found output silent payment address " + this.silentPaymentAddress);
                    break;
                case PSBT_OUT_SP_V0_LABEL:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 4) {
                        throw new PSBTParseException("PSBT output silent payment label must be 4 bytes");
                    }
                    this.silentPaymentLabel = Utils.readUint32(entry.getData(), 0);
                    log.debug("Found output silent payment label " + this.silentPaymentLabel);
                    break;
                case PSBT_OUT_DNSSEC_PROOF:
                    entry.checkOneByteKey();
                    this.dnssecProof = parseDnssecProof(entry.getData());
                    break;
                case PSBT_OUT_MWEB_STEALTH_ADDRESS:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 66) {
                        throw new PSBTParseException("PSBT output mweb stealth address must be 66 bytes");
                    }
                    this.mwebStealthAddress = new MwebAddress(entry.getData());
                    log.debug("Found output mweb stealth address " + this.mwebStealthAddress);
                    break;
                case PSBT_OUT_MWEB_OUTPUT_COMMIT:
                    entry.checkOneByteKey();
                    this.mwebOutputCommit = entry.getData();
                    log.debug("Found output mweb output commit " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_FEATURES:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 1) {
                        throw new PSBTParseException("PSBT output mweb output features must be 1 byte");
                    }
                    this.mwebFeatures = entry.getData()[0];
                    log.debug("Found output mweb output features " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_SENDER_PUBKEY:
                    entry.checkOneByteKey();
                    this.mwebSenderPubKey = ECKey.fromPublicOnly(entry.getData());
                    log.debug("Found output mweb sender pubkey " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_OUTPUT_PUBKEY:
                    entry.checkOneByteKey();
                    this.mwebOutputPubKey = ECKey.fromPublicOnly(entry.getData());
                    log.debug("Found output mweb output pubkey " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_STANDARD_FIELDS:
                    entry.checkOneByteKey();
                    if(entry.getData().length != 33+1+8+16) {
                        throw new PSBTParseException("PSBT output mweb standard fields must be 33+1+8+16 bytes");
                    }
                    this.mwebKeyExchangePubKey = ECKey.fromPublicOnly(Arrays.copyOfRange(entry.getData(), 0, 33));
                    log.debug("Found output mweb key exchange pubkey " + Utils.bytesToHex(mwebKeyExchangePubKey.getPubKey(true)));
                    this.mwebViewTag = entry.getData()[33];
                    log.debug("Found output mweb view tag " + mwebViewTag);
                    this.mwebEncryptedValue = Utils.readInt64(entry.getData(), 34);
                    log.debug("Found output mweb encrypted value " + mwebEncryptedValue);
                    this.mwebEncryptedNonce = Arrays.copyOfRange(entry.getData(), 42, 58);
                    log.debug("Found output mweb encrypted nonce " + Utils.bytesToHex(mwebEncryptedNonce));
                    break;
                case PSBT_OUT_MWEB_RANGE_PROOF:
                    entry.checkOneByteKey();
                    this.mwebRangeProof = entry.getData();
                    log.debug("Found output mweb range proof " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_EXTRA_DATA:
                    entry.checkOneByteKey();
                    this.mwebExtraData = entry.getData();
                    log.debug("Found output mweb extra data " + Utils.bytesToHex(entry.getData()));
                    break;
                case PSBT_OUT_MWEB_SIGNATURE:
                    entry.checkOneByteKey();
                    this.mwebSignature = SchnorrSignature.decode(entry.getData());
                    log.debug("Found output mweb output signature " + Utils.bytesToHex(entry.getData()));
                    break;
                default:
                    log.warn("PSBT output not recognized key type: " + entry.getKeyType());
            }
        }
    }

    public List<PSBTEntry> getOutputEntries(int psbtVersion) {
        List<PSBTEntry> entries = new ArrayList<>();

        if(redeemScript != null) {
            entries.add(populateEntry(PSBT_OUT_REDEEM_SCRIPT, null, redeemScript.getProgram()));
        }

        if(witnessScript != null) {
            entries.add(populateEntry(PSBT_OUT_WITNESS_SCRIPT, null, witnessScript.getProgram()));
        }

        for(Map.Entry<ECKey, KeyDerivation> entry : derivedPublicKeys.entrySet()) {
            entries.add(populateEntry(PSBT_OUT_BIP32_DERIVATION, entry.getKey().getPubKey(), serializeKeyDerivation(entry.getValue())));
        }

        if(psbtVersion >= 2) {
            if(amount != null) {
                byte[] amountBytes = new byte[8];
                Utils.int64ToByteArrayLE(amount, amountBytes, 0);
                entries.add(populateEntry(PSBT_OUT_AMOUNT, null, amountBytes));
            }
            if(script != null) {
                entries.add(populateEntry(PSBT_OUT_SCRIPT, null, script.getProgram()));
            }
            if(silentPaymentAddress != null) {
                entries.add(populateEntry(PSBT_OUT_SP_V0_INFO, null, Utils.concat(silentPaymentAddress.getScanKey().getPubKey(), silentPaymentAddress.getSpendKey().getPubKey())));
            }
            if(silentPaymentLabel != null) {
                byte[] labelBytes = new byte[4];
                Utils.uint32ToByteArrayLE(silentPaymentLabel, labelBytes, 0);
                entries.add(populateEntry(PSBT_OUT_SP_V0_LABEL, null, labelBytes));
            }
            if(mwebStealthAddress != null && mwebSignature == null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_STEALTH_ADDRESS, null, mwebStealthAddress.getData()));
            }
            if(mwebOutputCommit != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_OUTPUT_COMMIT, null, mwebOutputCommit));
            }
            if(mwebFeatures != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_FEATURES, null, new byte[]{mwebFeatures}));
            }
            if(mwebSenderPubKey != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_SENDER_PUBKEY, null, mwebSenderPubKey.getPubKey(true)));
            }
            if(mwebOutputPubKey != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_OUTPUT_PUBKEY, null, mwebOutputPubKey.getPubKey(true)));
            }
            if(mwebExtraData != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_EXTRA_DATA, null, mwebExtraData));
            }
            if(mwebKeyExchangePubKey != null && mwebViewTag != null && mwebEncryptedValue != null && mwebEncryptedNonce != null) {
                try {
                    var os = new ByteArrayOutputStream();
                    os.write(mwebKeyExchangePubKey.getPubKey(true));
                    os.write(mwebViewTag);
                    byte[] bs = new byte[8];
                    Utils.int64ToByteArrayLE(mwebEncryptedValue, bs, 0);
                    os.write(bs);
                    os.write(mwebEncryptedNonce);
                    entries.add(populateEntry(PSBT_OUT_MWEB_STANDARD_FIELDS, null, os.toByteArray()));
                } catch (IOException _) {
                }
            }
            if(mwebRangeProof != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_RANGE_PROOF, null, mwebRangeProof));
            }
            if(mwebSignature != null) {
                entries.add(populateEntry(PSBT_OUT_MWEB_SIGNATURE, null, mwebSignature.encode()));
            }
        }

        for(Map.Entry<String, String> entry : proprietary.entrySet()) {
            entries.add(populateEntry(PSBT_OUT_PROPRIETARY, Utils.hexToBytes(entry.getKey()), Utils.hexToBytes(entry.getValue())));
        }

        for(Map.Entry<ECKey, Map<KeyDerivation, List<Sha256Hash>>> entry : tapDerivedPublicKeys.entrySet()) {
            if(!entry.getValue().isEmpty()) {
                entries.add(populateEntry(PSBT_OUT_TAP_BIP32_DERIVATION, entry.getKey().getPubKeyXCoord(), serializeTaprootKeyDerivation(Collections.emptyList(), entry.getValue().keySet().iterator().next())));
            }
        }

        if(tapInternalKey != null) {
            entries.add(populateEntry(PSBT_OUT_TAP_INTERNAL_KEY, null, tapInternalKey.getPubKeyXCoord()));
        }

        if(dnssecProof != null) {
            entries.add(populateEntry(PSBT_OUT_DNSSEC_PROOF, null, serializeDnssecProof(dnssecProof)));
        }

        return entries;
    }

    void combine(PSBTOutput psbtOutput) {
        if(psbtOutput.redeemScript != null) {
            redeemScript = psbtOutput.redeemScript;
        }

        if(psbtOutput.witnessScript != null) {
            witnessScript = psbtOutput.witnessScript;
        }

        derivedPublicKeys.putAll(psbtOutput.derivedPublicKeys);

        if(psbtOutput.amount != null) {
            amount = psbtOutput.amount;
        }

        if(psbtOutput.script != null) {
            script = psbtOutput.script;
        }

        tapDerivedPublicKeys.putAll(psbtOutput.tapDerivedPublicKeys);

        if(psbtOutput.tapInternalKey != null) {
            tapInternalKey = psbtOutput.tapInternalKey;
        }

        if(psbtOutput.silentPaymentAddress != null) {
            silentPaymentAddress = psbtOutput.silentPaymentAddress;
        }

        if(psbtOutput.silentPaymentLabel != null) {
            silentPaymentLabel = psbtOutput.silentPaymentLabel;
        }

        if(psbtOutput.mwebStealthAddress != null) {
            mwebStealthAddress = psbtOutput.mwebStealthAddress;
        }

        if(psbtOutput.mwebOutputCommit != null) {
            mwebOutputCommit = psbtOutput.mwebOutputCommit;
        }

        if(psbtOutput.mwebFeatures != null) {
            mwebFeatures = psbtOutput.mwebFeatures;
        }

        if(psbtOutput.mwebSenderPubKey != null) {
            mwebSenderPubKey = psbtOutput.mwebSenderPubKey;
        }

        if(psbtOutput.mwebOutputPubKey != null) {
            mwebOutputPubKey = psbtOutput.mwebOutputPubKey;
        }

        if(psbtOutput.mwebKeyExchangePubKey != null) {
            mwebKeyExchangePubKey = psbtOutput.mwebKeyExchangePubKey;
        }

        if(psbtOutput.mwebViewTag != null) {
            mwebViewTag = psbtOutput.mwebViewTag;
        }

        if(psbtOutput.mwebEncryptedValue != null) {
            mwebEncryptedValue = psbtOutput.mwebEncryptedValue;
        }

        if(psbtOutput.mwebEncryptedNonce != null) {
            mwebEncryptedNonce = psbtOutput.mwebEncryptedNonce;
        }

        if(psbtOutput.mwebRangeProof != null) {
            mwebRangeProof = psbtOutput.mwebRangeProof;
        }

        if(psbtOutput.mwebSignature != null) {
            mwebSignature = psbtOutput.mwebSignature;
        }

        if(psbtOutput.mwebExtraData != null) {
            mwebExtraData = psbtOutput.mwebExtraData;
        }

        proprietary.putAll(psbtOutput.proprietary);
    }

    public Script getRedeemScript() {
        return redeemScript;
    }

    public void setRedeemScript(Script redeemScript) {
        this.redeemScript = redeemScript;
    }

    public Script getWitnessScript() {
        return witnessScript;
    }

    public void setWitnessScript(Script witnessScript) {
        this.witnessScript = witnessScript;
    }

    public KeyDerivation getKeyDerivation(ECKey publicKey) {
        return derivedPublicKeys.get(publicKey);
    }

    public Map<ECKey, KeyDerivation> getDerivedPublicKeys() {
        return derivedPublicKeys;
    }

    public Long getAmount() {
        if(psbt.getPsbtVersion() >= 2) {
            return amount;
        }

        return getOutput().getValue();
    }

    Long amount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Script getScript() {
        if(psbt.getPsbtVersion() >= 2) {
            if(mwebStealthAddress != null) {
                return mwebStealthAddress.getOutputScript();
            }
            return script;
        }

        return getOutput().getScript();
    }

    Script script() {
        return script;
    }

    public void setScript(Script script) {
        this.script = script;
    }

    public Map<String, String> getProprietary() {
        return proprietary;
    }

    public Map<ECKey, Map<KeyDerivation, List<Sha256Hash>>> getTapDerivedPublicKeys() {
        return tapDerivedPublicKeys;
    }

    public void setTapDerivedPublicKeys(Map<ECKey, Map<KeyDerivation, List<Sha256Hash>>> tapDerivedPublicKeys) {
        this.tapDerivedPublicKeys = tapDerivedPublicKeys;
    }

    public ECKey getTapInternalKey() {
        return tapInternalKey;
    }

    public void setTapInternalKey(ECKey tapInternalKey) {
        this.tapInternalKey = tapInternalKey;
    }

    public SilentPaymentAddress getSilentPaymentAddress() {
        return silentPaymentAddress;
    }

    public void setSilentPaymentAddress(SilentPaymentAddress silentPaymentAddress) {
        this.silentPaymentAddress = silentPaymentAddress;
    }

    public Long getSilentPaymentLabel() {
        return silentPaymentLabel;
    }

    public void setSilentPaymentLabel(Long silentPaymentLabel) {
        this.silentPaymentLabel = silentPaymentLabel;
    }

    public Map<String, byte[]> getDnssecProof() {
        return dnssecProof;
    }

    public Optional<DnsPayment> getDnsPayment() throws DnsPaymentValidationException, IOException, BitcoinURIParseException, ExecutionException, InterruptedException {
        if(dnssecProof == null || dnssecProof.isEmpty()) {
            return Optional.empty();
        }

        String hrn = dnssecProof.keySet().iterator().next();
        DnsPaymentResolver resolver = new DnsPaymentResolver(hrn);
        return resolver.resolve(dnssecProof.get(hrn));
    }

    public void setDnssecProof(Map<String, byte[]> dnssecProof) {
        this.dnssecProof = dnssecProof;
    }

    public MwebAddress getMwebStealthAddress() {
        return mwebStealthAddress;
    }

    public void setMwebStealthAddress(MwebAddress address) {
        mwebStealthAddress = address;
    }

    public TransactionOutput getOutput() {
        return psbt.getTransaction().getOutputs().get(index);
    }

    void setIndex(int index) {
        this.index = index;
    }

    public void clearNonFinalFields() {
        tapDerivedPublicKeys.clear();
    }

    public boolean isMweb() {
        return mwebStealthAddress != null || mwebOutputCommit != null;
    }

    public boolean isMwebSane() {
        if (mwebStealthAddress == null && mwebOutputCommit == null) {
            return false;
        }
        if (mwebSignature != null) {
            if (mwebOutputCommit == null || mwebFeatures == null || mwebSenderPubKey == null ||
                mwebOutputPubKey == null || mwebRangeProof == null) {
                return false;
            }
            if ((mwebFeatures & 1) > 0 && (mwebKeyExchangePubKey == null || mwebViewTag == null ||
                                           mwebEncryptedValue == null || mwebEncryptedNonce == null)) {
                return false;
            }
            if ((mwebFeatures & 2) > 0 && mwebExtraData.length == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isFinalized() {
        return !isMweb() || mwebSignature != null;
    }
}
