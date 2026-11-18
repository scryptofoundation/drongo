package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.sparrowwallet.drongo.protocol.ScriptOpCodes.*;

/** An inscription's content as its transactions carry it, read without an indexer. */
public record InscriptionContent(String contentType, String contentEncoding, byte[] body) {
    private static final byte[] ORD = "ord".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TAG_CONTENT_TYPE = {1};
    private static final byte[] TAG_CONTENT_ENCODING = {9};

    /** The index-th envelope of an ord reveal, counted across inputs as the inscription ids are, or null when there is none. */
    public static InscriptionContent ord(Transaction reveal, int index) {
        int seen = 0;
        for(TransactionInput input : reveal.getInputs()) {
            if(input.getWitness() == null) {
                continue;
            }
            for(byte[] push : input.getWitness().getPushes()) {
                List<ScriptChunk> chunks;
                try {
                    chunks = new Script(push).getChunks();
                } catch(Exception e) {
                    continue;
                }
                for(int i = 0; i + 2 < chunks.size(); i++) {
                    if(chunks.get(i).opcode == OP_0 && chunks.get(i + 1).opcode == OP_IF && Arrays.equals(chunks.get(i + 2).data, ORD)) {
                        if(seen++ == index) {
                            return envelope(chunks, i + 3);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static InscriptionContent envelope(List<ScriptChunk> chunks, int from) {
        String contentType = null;
        String contentEncoding = null;
        int i = from;
        for(; i + 1 < chunks.size(); i += 2) {
            ScriptChunk tag = chunks.get(i);
            if(tag.opcode == OP_0 || tag.opcode == OP_ENDIF) {
                break;
            }
            byte[] value = chunks.get(i + 1).data;
            if(tag.data != null && value != null) {
                if(contentType == null && Arrays.equals(tag.data, TAG_CONTENT_TYPE)) {
                    contentType = new String(value, StandardCharsets.UTF_8);
                } else if(contentEncoding == null && Arrays.equals(tag.data, TAG_CONTENT_ENCODING)) {
                    contentEncoding = new String(value, StandardCharsets.UTF_8);
                }
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if(i < chunks.size() && chunks.get(i).opcode == OP_0) {
            for(i = i + 1; i < chunks.size() && chunks.get(i).opcode != OP_ENDIF; i++) {
                if(chunks.get(i).data != null) {
                    body.writeBytes(chunks.get(i).data);
                }
            }
        }
        return new InscriptionContent(contentType, contentEncoding, body.toByteArray());
    }

    /** The most steps a Doginals chain is walked back to its first. */
    public static final int MAX_DOGINALS_STEPS = 2_000;

    /**
     * Reads an inscription's content from the chain: an ord id from its reveal; a Doginals id by walking input 0 back
     * from the chain's last step (lastStep, the mint transaction a trace found) to the step the id names. Null when the
     * transactions hold no such inscription, or a Doginals id comes without its last step.
     */
    public static InscriptionContent fromChain(String inscriptionId, Sha256Hash lastStep, ChainSource source) throws java.io.IOException {
        int at = inscriptionId.lastIndexOf('i');
        if(at != 64) {
            return null;
        }
        Sha256Hash txid = Sha256Hash.wrap(inscriptionId.substring(0, at));
        int index = Integer.parseInt(inscriptionId.substring(at + 1));
        Transaction named = source.transaction(txid).tx();
        int ordIndex = InscriptionLocator.ordIds(named).indexOf(inscriptionId);
        if(ordIndex >= 0) {
            return ord(named, ordIndex);
        }
        if(lastStep == null || index != 0) {
            return null;
        }
        java.util.LinkedList<Transaction> steps = new java.util.LinkedList<>();
        Transaction current = source.transaction(lastStep).tx();
        for(int i = 0; i < MAX_DOGINALS_STEPS; i++) {
            steps.addFirst(current);
            java.util.Optional<DoginalsStep> step = InscriptionLocator.doginals(current);
            if(step.isEmpty()) {
                return null;
            }
            if(step.get().genesis()) {
                return current.getTxId().equals(txid) ? doginals(steps) : null;
            }
            current = source.transaction(step.get().previousTxid()).tx();
        }
        return null;
    }

    /**
     * A Doginals inscription from its chain's transactions, first step to last: each scriptSig's pushes before the
     * signature and the redeem script, joined, are "ord", the part count, the content type, then countdown and part pairs.
     */
    public static InscriptionContent doginals(List<Transaction> genesisToLast) {
        List<ScriptChunk> envelope = new ArrayList<>();
        for(Transaction step : genesisToLast) {
            List<ScriptChunk> chunks = step.getInputs().getFirst().getScriptSig().getChunks();
            envelope.addAll(chunks.subList(0, Math.max(0, chunks.size() - 2)));
        }
        if(envelope.size() < 3 || !Arrays.equals(envelope.getFirst().data, ORD)) {
            return null;
        }
        String contentType = envelope.get(2).data == null ? null : new String(envelope.get(2).data, StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for(int i = 4; i < envelope.size(); i += 2) {
            if(envelope.get(i).data != null) {
                body.writeBytes(envelope.get(i).data);
            }
        }
        return new InscriptionContent(contentType, null, body.toByteArray());
    }
}
