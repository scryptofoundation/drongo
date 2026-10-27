package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

public class Erc20TemplatesTest {
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41d4d1a8d4ac34f5ae1b8a8c4f4bd5eb1fb3");

    private static Erc20Templates.Options options(Erc20Templates.Supply supply, boolean all) {
        return new Erc20Templates.Options(supply, all, all, all, all, all, all);
    }

    @Test
    public void contractAddressFollowsCreateRule() {
        EvmAddress sender = EvmAddress.fromString("0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0");
        Assertions.assertEquals(EvmAddress.fromString("0xcd234a471b72ba2f1ccf0a70fcaba648a5eecd8d"), EvmAddress.contractAddress(sender, BigInteger.ZERO));
        Assertions.assertEquals(EvmAddress.fromString("0x343c43a37d37dff08ae8c4a11544c718abb4fcf8"), EvmAddress.contractAddress(sender, BigInteger.ONE));
        Assertions.assertEquals(EvmAddress.fromString("0xf778b86fa74e846c4f0a1fbd1335fe81c00a0c91"), EvmAddress.contractAddress(sender, BigInteger.TWO));
    }

    @Test
    public void creationTransactionHasNoRecipient() {
        ECKey key = ECKey.fromPrivate(BigInteger.valueOf(123456789));
        byte[] code = Utils.hexToBytes("6080604052");
        Eip1559Transaction creation = new Eip1559Transaction(4441L, BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(100000), null, BigInteger.ZERO, code);
        Eip1559Transaction call = new Eip1559Transaction(4441L, BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(100000), ME, BigInteger.ZERO, code);
        Assertions.assertTrue(creation.isCreation());
        Assertions.assertFalse(call.isCreation());
        //The recipient is RLP's empty string (one byte, 0x80) instead of a 21-byte address field
        Assertions.assertEquals(call.unsignedSize() - 20, creation.unsignedSize());
        Eip1559Transaction.Signed signed = creation.sign(key);
        Assertions.assertEquals(EvmAddress.fromPublicKey(key), signed.from());
    }

    @Test
    public void keyNamesSupplyAndFeatures() {
        Assertions.assertEquals("capped_bpevfc", options(Erc20Templates.Supply.CAPPED, true).key());
        Assertions.assertEquals("fixed", options(Erc20Templates.Supply.FIXED, false).key());
        Assertions.assertEquals("mintable_v", new Erc20Templates.Options(Erc20Templates.Supply.MINTABLE, false, false, false, true, false, false).key());
    }

    @Test
    public void everyCombinationHasATemplate() {
        Erc20Templates templates = Erc20Templates.get();
        Assertions.assertEquals(192, templates.keys().size());
        for(Erc20Templates.Supply supply : Erc20Templates.Supply.values()) {
            for(int mask = 0; mask < 64; mask++) {
                Erc20Templates.Options o = new Erc20Templates.Options(supply, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0, (mask & 32) != 0);
                Assertions.assertTrue(templates.keys().contains(o.key()), o.key());
            }
        }
        Assertions.assertEquals("v0.8.36+commit.8a079791", templates.compilerVersion());
    }

    @Test
    public void ownerOnlyWhenSomethingNeedsOne() {
        Assertions.assertFalse(options(Erc20Templates.Supply.FIXED, false).ownable());
        Assertions.assertTrue(new Erc20Templates.Options(Erc20Templates.Supply.FIXED, false, true, false, false, false, false).ownable());
        Assertions.assertTrue(options(Erc20Templates.Supply.MINTABLE, false).ownable());
    }

    @Test
    public void fixedCreationCodeAppendsItsArguments() {
        Erc20Templates templates = Erc20Templates.get();
        Erc20Templates.Options fixed = options(Erc20Templates.Supply.FIXED, false);
        Erc20Templates.Params params = new Erc20Templates.Params("My Token", "MYT", ME, BigInteger.valueOf(1000), ME, null);
        byte[] code = templates.creationCode(fixed, params);
        byte[] args = Abi.encodeArguments("My Token", "MYT", ME, BigInteger.valueOf(1000));
        Assertions.assertArrayEquals(args, Arrays.copyOfRange(code, code.length - args.length, code.length));
        Assertions.assertArrayEquals(templates.bytecode(fixed), Arrays.copyOf(code, code.length - args.length));
        Assertions.assertArrayEquals(args, templates.constructorArguments(fixed, params));
    }

    @Test
    public void cappedArgumentsAddOwnerThenCap() {
        Erc20Templates templates = Erc20Templates.get();
        Erc20Templates.Options capped = options(Erc20Templates.Supply.CAPPED, true);
        EvmAddress owner = EvmAddress.fromString("0x000000000000000000000000000000000000dead");
        Erc20Templates.Params params = new Erc20Templates.Params("Capped", "CAP", ME, BigInteger.TEN, owner, BigInteger.valueOf(100));
        Assertions.assertArrayEquals(Abi.encodeArguments("Capped", "CAP", ME, BigInteger.TEN, owner, BigInteger.valueOf(100)),
                templates.constructorArguments(capped, params));
    }

    @Test
    public void encodeArgumentsIsACallWithoutItsSelector() {
        byte[] call = Abi.encodeCall("f(string,address,uint256)", "abc", ME, BigInteger.ONE);
        Assertions.assertArrayEquals(Arrays.copyOfRange(call, 4, call.length), Abi.encodeArguments("abc", ME, BigInteger.ONE));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void verificationInputHasExactlyTheVariantSources() {
        Erc20Templates templates = Erc20Templates.get();
        Erc20Templates.Options o = new Erc20Templates.Options(Erc20Templates.Supply.MINTABLE, false, false, false, true, false, false);
        Map<String, Object> input = (Map<String, Object>)MiniJson.parse(templates.verificationInput(o));
        Assertions.assertEquals("Solidity", input.get("language"));
        Map<String, Object> sources = (Map<String, Object>)input.get("sources");
        Assertions.assertTrue(sources.containsKey("Token.sol"));
        Assertions.assertTrue(sources.containsKey("@openzeppelin/contracts/token/ERC20/extensions/ERC20Votes.sol"));
        Assertions.assertFalse(sources.containsKey("@openzeppelin/contracts/token/ERC20/extensions/ERC20Permit.sol"));
        String token = (String)((Map<String, Object>)sources.get("Token.sol")).get("content");
        Assertions.assertEquals(templates.source(o), token);
        Assertions.assertTrue(token.contains("contract Token is ERC20, Ownable, ERC20Votes"));
        Map<String, Object> settings = (Map<String, Object>)input.get("settings");
        Assertions.assertEquals("cancun", settings.get("evmVersion"));
        Map<String, Object> optimizer = (Map<String, Object>)settings.get("optimizer");
        Assertions.assertEquals(Boolean.TRUE, optimizer.get("enabled"));
        Assertions.assertEquals(0, new java.math.BigDecimal(200).compareTo((java.math.BigDecimal)optimizer.get("runs")));
    }

    @Test
    public void jsonEscapesSurviveARoundTrip() {
        String text = "line\n\"quoted\" \\ tab\t \u0001 é";
        Assertions.assertEquals(text, MiniJson.parse(Erc20Templates.quote(text)));
    }

    @Test
    public void paramsAreChecked() {
        Erc20Templates.Options fixed = options(Erc20Templates.Supply.FIXED, false);
        Erc20Templates.Options capped = options(Erc20Templates.Supply.CAPPED, false);
        Erc20Templates.Options mintable = options(Erc20Templates.Supply.MINTABLE, false);
        Erc20Templates.Options votes = new Erc20Templates.Options(Erc20Templates.Supply.MINTABLE, false, false, false, true, false, false);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("", "T", ME, BigInteger.ONE, ME, null).check(fixed));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", " ", ME, BigInteger.ONE, ME, null).check(fixed));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Na‮me", "T", ME, BigInteger.ONE, ME, null).check(fixed));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("N".repeat(65), "T", ME, BigInteger.ONE, ME, null).check(fixed));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T".repeat(17), ME, BigInteger.ONE, ME, null).check(fixed));
        //A fixed supply of nothing could never hold a token
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.ZERO, ME, null).check(fixed));
        new Erc20Templates.Params("Name", "T", ME, BigInteger.ZERO, ME, null).check(mintable);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.ONE, ME, null).check(capped));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.ONE, ME, BigInteger.ZERO).check(capped));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.TEN, ME, BigInteger.ONE).check(capped));
        new Erc20Templates.Params("Name", "T", ME, BigInteger.ONE, ME, BigInteger.ONE).check(capped);
        BigInteger votesMax = BigInteger.TWO.pow(208).subtract(BigInteger.ONE);
        new Erc20Templates.Params("Name", "T", ME, votesMax, ME, null).check(votes);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, votesMax.add(BigInteger.ONE), ME, null).check(votes));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.TWO.pow(256), ME, null).check(mintable));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Templates.Params("Name", "T", ME, BigInteger.ONE, null, null).check(mintable));
    }

    @Test
    public void ownerCallsUseTheStandardSelectors() {
        Assertions.assertEquals("40c10f19", Utils.bytesToHex(Arrays.copyOf(Erc20Admin.mint(ME, BigInteger.ONE), 4)));
        Assertions.assertEquals("8456cb59", Utils.bytesToHex(Erc20Admin.pause()));
        Assertions.assertEquals("3f4ba83a", Utils.bytesToHex(Erc20Admin.unpause()));
        Assertions.assertEquals("42966c68", Utils.bytesToHex(Arrays.copyOf(Erc20Admin.burn(BigInteger.ONE), 4)));
        Assertions.assertEquals("f2fde38b", Utils.bytesToHex(Arrays.copyOf(Erc20Admin.transferOwnership(ME), 4)));
        Assertions.assertEquals("715018a6", Utils.bytesToHex(Erc20Admin.renounceOwnership()));
        Assertions.assertEquals("8da5cb5b", Utils.bytesToHex(Erc20Admin.owner()));
        Assertions.assertEquals("5c975abb", Utils.bytesToHex(Erc20Admin.paused()));
        Assertions.assertEquals("355274ea", Utils.bytesToHex(Erc20Admin.cap()));
    }
}
