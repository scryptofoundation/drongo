package com.sparrowwallet.drongo.wallet;

/**
 * How swaps with one maker have gone for this wallet: the maker's public key and how many swaps with it completed, failed or saw the maker not show.
 */
public class MakerHistoryEntry extends Persistable {
    private String makerPubKey;
    private int completed;
    private int failed;
    private int noShow;

    public MakerHistoryEntry(String makerPubKey, int completed, int failed, int noShow) {
        this.makerPubKey = makerPubKey;
        this.completed = completed;
        this.failed = failed;
        this.noShow = noShow;
    }

    public String getMakerPubKey() {
        return makerPubKey;
    }

    public void setMakerPubKey(String makerPubKey) {
        this.makerPubKey = makerPubKey;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(int completed) {
        this.completed = completed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public int getNoShow() {
        return noShow;
    }

    public void setNoShow(int noShow) {
        this.noShow = noShow;
    }

    public MakerHistoryEntry copy() {
        MakerHistoryEntry copy = new MakerHistoryEntry(makerPubKey, completed, failed, noShow);
        copy.setId(getId());
        return copy;
    }
}
