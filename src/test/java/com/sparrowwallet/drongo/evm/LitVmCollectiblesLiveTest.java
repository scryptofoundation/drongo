package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Read-only, against the LiteForge testnet; set LITVM_LIVE=1 to run. The throwaway test account holds the World 1
 * Vault Badge: it must be listed with its name and a picture the SVG subset accepts. Nothing is sent.
 */
public class LitVmCollectiblesLiveTest {
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");

    @Test
    public void theTestAccountsBadgeIsListedWithItsPicture() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, EvmTransport.direct());
        client.verifyChain();
        Assertions.assertTrue(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, BADGE));

        Optional<Erc721Collection> collection = Erc721.read(client, BADGE);
        Assertions.assertTrue(collection.isPresent(), "the badge contract must read as a collection");
        System.out.println("NFT collection " + collection.get());
        Assertions.assertTrue(collection.get().soulbound());
        Assertions.assertFalse(collection.get().enumerable());

        //A token the account does not hold: a real revert must read as "no owner", not as a failure
        BigInteger notHeld = new BigInteger(1, HOLDER.getBytes()).shiftLeft(8).or(BigInteger.valueOf(4));
        Assertions.assertTrue(Erc721.ownerOf(client, BADGE, notHeld).isEmpty(), "an unminted badge has no owner");
        Assertions.assertTrue(Erc721.tokenUri(client, BADGE, notHeld).isEmpty(), "an unminted badge has no token URI");

        NftLister.Result result = NftLister.list(client, BADGE, HOLDER);
        Assertions.assertInstanceOf(NftLister.Listed.class, result);
        NftLister.Listed listed = (NftLister.Listed)result;
        System.out.println("NFT listed " + listed.tokens().size() + " more " + listed.more());
        Assertions.assertFalse(listed.tokens().isEmpty(), "the test account holds at least the World 1 badge");

        boolean worldOne = false;
        for(Nft nft : listed.tokens()) {
            Assertions.assertTrue(nft.metadata().isPresent(), "each badge has readable metadata");
            NftMetadata metadata = nft.metadata().get();
            System.out.println("NFT token " + nft.tokenId().toString(16) + " name " + metadata.name() + " attributes " + metadata.attributes());
            Assertions.assertInstanceOf(NftMetadata.Image.Svg.class, metadata.image());
            Optional<SvgSubset.Drawing> drawing = SvgSubset.parse(((NftMetadata.Image.Svg)metadata.image()).source());
            Assertions.assertTrue(drawing.isPresent(), "the badge picture must be drawable, not a placeholder");
            System.out.println("NFT picture " + drawing.get().width() + " x " + drawing.get().height() + ", " + drawing.get().shapes().size() + " shapes");
            if("Super Litecoin Vault Badge: World 1".equals(metadata.name())) {
                worldOne = true;
            }
        }
        Assertions.assertTrue(worldOne, "World 1 must be among the listed badges");
    }
}
