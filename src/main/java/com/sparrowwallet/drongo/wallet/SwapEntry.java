package com.sparrowwallet.drongo.wallet;

/**
 * One atomic swap of the wallet, as stored in the wallet file. The swap record itself is only held sealed (encrypted to the wallet file's
 * encryption key), so this holds no plaintext record and no key: the swap id, the swap key index the swap derives its keys from, a status
 * readable without opening the record, the sealed record and when it was last written.
 */
public class SwapEntry extends Persistable {
    private String swapId;
    private int keyIndex;
    private String status;
    private byte[] sealed;
    private long updatedSeconds;

    public SwapEntry(String swapId, int keyIndex, String status, byte[] sealed, long updatedSeconds) {
        this.swapId = swapId;
        this.keyIndex = keyIndex;
        this.status = status;
        this.sealed = sealed == null ? null : sealed.clone();
        this.updatedSeconds = updatedSeconds;
    }

    public String getSwapId() {
        return swapId;
    }

    public void setSwapId(String swapId) {
        this.swapId = swapId;
    }

    public int getKeyIndex() {
        return keyIndex;
    }

    public void setKeyIndex(int keyIndex) {
        this.keyIndex = keyIndex;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public byte[] getSealed() {
        return sealed == null ? null : sealed.clone();
    }

    public void setSealed(byte[] sealed) {
        this.sealed = sealed == null ? null : sealed.clone();
    }

    public long getUpdatedSeconds() {
        return updatedSeconds;
    }

    public void setUpdatedSeconds(long updatedSeconds) {
        this.updatedSeconds = updatedSeconds;
    }

    public SwapEntry copy() {
        SwapEntry copy = new SwapEntry(swapId, keyIndex, status, sealed, updatedSeconds);
        copy.setId(getId());
        return copy;
    }
}
