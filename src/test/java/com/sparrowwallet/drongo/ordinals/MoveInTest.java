package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class MoveInTest {
    private Coin previousCoin;
    private Address taproot1;
    private Address taproot2;
    private Address runesDestination;
    private Address plainDestination;

    @BeforeEach
    public void setUp() {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
        Network.set(Network.REGTEST);
        taproot1 = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(101)));
        taproot2 = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(102)));
        runesDestination = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(103)));
        plainDestination = ScriptType.P2WPKH.getAddress(ECKey.fromPrivate(BigInteger.valueOf(104)));
    }

    @AfterEach
    public void tearDown() {
        Network.set(null);
        Coin.endScope(previousCoin);
    }

    /** A dumped key for a small secret, and one previous transaction paying each value to that key's P2WPKH address. */
    private List<MoveIn.Found> found(long secret, long[] values, List<List<String>> inscriptions, boolean[] runic) {
        byte[] payload = new byte[33];
        System.arraycopy(com.sparrowwallet.drongo.Utils.bigIntegerToBytes(BigInteger.valueOf(secret), 32), 0, payload, 0, 32);
        payload[32] = 1;
        String line = Base58.encodeChecked(0xEF, payload) + " 2026-10-27T01:44:34Z label= # addr=x\n";
        OrdWalletDump.DumpedKey key = OrdWalletDump.parse(new StringReader(line), Network.REGTEST).getFirst();
        Address address = ScriptType.P2WPKH.getAddress(key.publicKey());
        Transaction prev = new Transaction();
        prev.addInput(Sha256Hash.ZERO_HASH, secret, new Script(new byte[] {(byte)secret}));
        for(long value : values) {
            prev.addOutput(value, address);
        }
        List<MoveIn.Found> found = new ArrayList<>();
        for(int i = 0; i < values.length; i++) {
            found.add(new MoveIn.Found(prev.getOutputs().get(i), key, ScriptType.P2WPKH, inscriptions.get(i), runic[i]));
        }
        return found;
    }

    @Test
    public void sweepKeepsInscriptionValuesOneToOne() {
        List<MoveIn.Found> found = found(7, new long[] {500_000, 10_000, 3_000_000},
                List.of(List.of(), List.of("aa".repeat(32) + "i0"), List.of()), new boolean[] {false, false, false});
        found.addAll(found(8, new long[] {12_345}, List.of(List.of("bb".repeat(32) + "i0", "bb".repeat(32) + "i1")), new boolean[] {false}));
        Transaction tx = MoveIn.sweep(found, List.of(taproot1, taproot2), runesDestination, plainDestination, 2.0, 1.0);
        //Inscribed inputs first, in the order found, each to its own destination with its own value
        Assertions.assertEquals(10_000, tx.getOutputs().get(0).getValue());
        Assertions.assertEquals(taproot1.getOutputScript(), tx.getOutputs().get(0).getScript());
        Assertions.assertEquals(12_345, tx.getOutputs().get(1).getValue());
        Assertions.assertEquals(taproot2.getOutputScript(), tx.getOutputs().get(1).getScript());
        Assertions.assertEquals(found.get(1).output().getHash(), tx.getInputs().get(0).getOutpoint().getHash());
        Assertions.assertEquals(1, tx.getInputs().get(0).getOutpoint().getIndex());
        Assertions.assertEquals(found.get(3).output().getHash(), tx.getInputs().get(1).getOutpoint().getHash());
        Assertions.assertEquals(3, tx.getOutputs().size(), "no runes, so no runes output and no runestone");
        Assertions.assertEquals(plainDestination.getOutputScript(), tx.getOutputs().get(2).getScript());
    }

    @Test
    public void runesGoToPointerOutput() {
        List<MoveIn.Found> found = found(9, new long[] {10_000, 10_000, 1_000_000},
                List.of(List.of("cc".repeat(32) + "i0"), List.of(), List.of()), new boolean[] {false, true, false});
        Transaction tx = MoveIn.sweep(found, List.of(taproot1), runesDestination, plainDestination, 2.0, 1.0);
        List<TransactionOutput> outputs = tx.getOutputs();
        Assertions.assertEquals(4, outputs.size());
        Assertions.assertEquals(taproot1.getOutputScript(), outputs.get(0).getScript());
        Assertions.assertEquals(runesDestination.getOutputScript(), outputs.get(1).getScript());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, outputs.get(1).getValue());
        Assertions.assertEquals(plainDestination.getOutputScript(), outputs.get(2).getScript());
        Assertions.assertEquals(Runestone.script(List.of(), 1), outputs.get(3).getScript());
        //The runic input comes right after the inscribed one
        Assertions.assertEquals(1, tx.getInputs().get(1).getOutpoint().getIndex());
    }

    @Test
    public void noRunestoneWithoutRunicInputs() {
        List<MoveIn.Found> found = found(10, new long[] {10_000, 1_000_000}, List.of(List.of("dd".repeat(32) + "i0"), List.of()), new boolean[] {false, false});
        Transaction tx = MoveIn.sweep(found, List.of(taproot1), runesDestination, plainDestination, 2.0, 1.0);
        Assertions.assertTrue(tx.getOutputs().stream().noneMatch(output -> output.getScript().getProgram().length > 0 && (output.getScript().getProgram()[0] & 0xff) == ScriptOpCodes.OP_RETURN));
    }

    @Test
    public void sweepRefusesWhenPlainCannotPayFee() {
        List<MoveIn.Found> onlyInscribed = found(11, new long[] {10_000}, List.of(List.of("ee".repeat(32) + "i0")), new boolean[] {false});
        Assertions.assertThrows(IllegalArgumentException.class, () -> MoveIn.sweep(onlyInscribed, List.of(taproot1), runesDestination, plainDestination, 2.0, 1.0));
        List<MoveIn.Found> runicNoPlain = found(12, new long[] {10_000}, List.of(List.of()), new boolean[] {true});
        Assertions.assertThrows(IllegalArgumentException.class, () -> MoveIn.sweep(runicNoPlain, List.of(), runesDestination, plainDestination, 2.0, 1.0));
    }

    @Test
    public void destinationsMustMatchInscribedInputs() {
        List<MoveIn.Found> found = found(13, new long[] {10_000, 1_000_000}, List.of(List.of("ff".repeat(32) + "i0"), List.of()), new boolean[] {false, false});
        Assertions.assertThrows(IllegalArgumentException.class, () -> MoveIn.sweep(found, List.of(), runesDestination, plainDestination, 2.0, 1.0));
    }

    @Test
    public void everyInputIsSigned() throws Exception {
        List<MoveIn.Found> found = found(14, new long[] {10_000, 10_000, 1_000_000}, List.of(List.of("ab".repeat(32) + "i0"), List.of(), List.of()), new boolean[] {false, true, false});
        Transaction tx = MoveIn.sweep(found, List.of(taproot1), runesDestination, plainDestination, 2.0, 1.0);
        //Inputs are inscribed, runic, plain: the same order as found here
        for(int i = 0; i < tx.getInputs().size(); i++) {
            TransactionInput input = tx.getInputs().get(i);
            List<byte[]> pushes = input.getWitness().getPushes();
            Assertions.assertEquals(2, pushes.size());
            ECKey pubKey = ECKey.fromPublicOnly(pushes.get(1));
            Sha256Hash hash = tx.hashForWitnessSignature(i, ScriptType.P2PKH.getOutputScript(pubKey), found.get(i).output().getValue(), SigHash.ALL);
            TransactionSignature signature = TransactionSignature.decodeFromBitcoin(pushes.get(0), true);
            Assertions.assertTrue(pubKey.verify(hash.getBytes(), signature), "input " + i);
        }
    }
}
