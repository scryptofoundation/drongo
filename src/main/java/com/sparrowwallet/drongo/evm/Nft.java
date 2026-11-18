package com.sparrowwallet.drongo.evm;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * One NFT an account holds: identified by its collection's contract and its token id, never by anything in its
 * metadata. The metadata is empty when the token URI could not be read.
 */
public record Nft(Erc721Collection collection, BigInteger tokenId, Optional<NftMetadata> metadata) {
    public Nft {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(metadata, "metadata");
    }
}
