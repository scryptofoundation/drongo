package com.sparrowwallet.drongo.address;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public class AddressTest {
    @Test
    public void validAddressTest() throws InvalidAddressException {
        Address address1 = Address.fromString("ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9");
        Assertions.assertTrue(address1 instanceof P2WPKHAddress);
        Assertions.assertEquals("ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9", address1.toString());

        Address address2 = Address.fromString("ltc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qmu8tk5");
        Assertions.assertTrue(address2 instanceof P2WSHAddress);
        Assertions.assertEquals("ltc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qmu8tk5", address2.toString());

        Address address3 = Address.fromString("LTfmQqeYrxD6t8VBi6iNKpyfmiY5Z3bdyv");
        Assertions.assertTrue(address3 instanceof P2PKHAddress);
        Assertions.assertEquals("LTfmQqeYrxD6t8VBi6iNKpyfmiY5Z3bdyv", address3.toString());

        Address address4 = Address.fromString("MAww38m2PiRbuuQUTmMZ5mJYk6c8TVHAxe");
        Assertions.assertTrue(address4 instanceof P2SHAddress);
        Assertions.assertEquals("MAww38m2PiRbuuQUTmMZ5mJYk6c8TVHAxe", address4.toString());

        Address address5 = Address.fromString(Network.TESTNET, "tltc1qawkzyj2l5yck5jq4wyhkc4837x088580ad76xw");
        Assertions.assertTrue(address5 instanceof P2WPKHAddress);
        Assertions.assertEquals("tltc1qawkzyj2l5yck5jq4wyhkc4837x088580ad76xw", address5.toString(Network.TESTNET));

        Address address6 = Address.fromString(Network.TESTNET, "tltc1q8kdkthp5a6vfrdas84efkpv25ul3s9wpzc755cra8av48xq4a7wsdmvvyz");
        Assertions.assertTrue(address6 instanceof P2WSHAddress);
        Assertions.assertEquals("tltc1q8kdkthp5a6vfrdas84efkpv25ul3s9wpzc755cra8av48xq4a7wsdmvvyz", address6.toString(Network.TESTNET));

        Address address7 = Address.fromString(Network.TESTNET, "mng6R5oLWBBo8iFWU9Mx4zFy5pWhrWMeW2");
        Assertions.assertTrue(address7 instanceof P2PKHAddress);
        Assertions.assertEquals("mng6R5oLWBBo8iFWU9Mx4zFy5pWhrWMeW2", address7.toString(Network.TESTNET));

        Address address8 = Address.fromString(Network.TESTNET, "n1S1rnnZm3RdW9iuAF6Hjk3gLZWGc59zDi");
        Assertions.assertTrue(address8 instanceof P2PKHAddress);
        Assertions.assertEquals("n1S1rnnZm3RdW9iuAF6Hjk3gLZWGc59zDi", address8.toString(Network.TESTNET));

        Address address9 = Address.fromString(Network.TESTNET, "QfvF1VkLi6b4Ku9H2p7zVgJ7ZV6vBUR3zH");
        Assertions.assertTrue(address9 instanceof P2SHAddress);
        Assertions.assertEquals("QfvF1VkLi6b4Ku9H2p7zVgJ7ZV6vBUR3zH", address9.toString(Network.TESTNET));

        Address address10 = Address.fromString(Network.SIGNET, "2NCZUtUt6gzXyBiPEQi5yQyrgR6f6F6Ki6A");
        Assertions.assertTrue(address10 instanceof P2SHAddress);
        Assertions.assertEquals("2NCZUtUt6gzXyBiPEQi5yQyrgR6f6F6Ki6A", address10.toString(Network.SIGNET));

        Address address11 = Address.fromString("ltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpj6zg2");
        Assertions.assertTrue(address11 instanceof P2TRAddress);
        Assertions.assertEquals("ltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpj6zg2", address11.toString());

        Address address12 = Address.fromString(Network.TESTNET, "tltc1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvseskjtjs8");
        Assertions.assertTrue(address12 instanceof P2TRAddress);
        Assertions.assertEquals("tltc1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvseskjtjs8", address12.toString(Network.TESTNET));
    }

    @Test
    public void testnetValidAddressTest() throws InvalidAddressException {
        Network.set(Network.TESTNET);

        Address address5 = Address.fromString("tltc1qawkzyj2l5yck5jq4wyhkc4837x088580ad76xw");
        Assertions.assertTrue(address5 instanceof P2WPKHAddress);
        Assertions.assertEquals("tltc1qawkzyj2l5yck5jq4wyhkc4837x088580ad76xw", address5.toString());

        Address address6 = Address.fromString("tltc1q8kdkthp5a6vfrdas84efkpv25ul3s9wpzc755cra8av48xq4a7wsdmvvyz");
        Assertions.assertTrue(address6 instanceof P2WSHAddress);
        Assertions.assertEquals("tltc1q8kdkthp5a6vfrdas84efkpv25ul3s9wpzc755cra8av48xq4a7wsdmvvyz", address6.toString());

        Address address7 = Address.fromString("mng6R5oLWBBo8iFWU9Mx4zFy5pWhrWMeW2");
        Assertions.assertTrue(address7 instanceof P2PKHAddress);
        Assertions.assertEquals("mng6R5oLWBBo8iFWU9Mx4zFy5pWhrWMeW2", address7.toString());

        Address address8 = Address.fromString("n1S1rnnZm3RdW9iuAF6Hjk3gLZWGc59zDi");
        Assertions.assertTrue(address8 instanceof P2PKHAddress);
        Assertions.assertEquals("n1S1rnnZm3RdW9iuAF6Hjk3gLZWGc59zDi", address8.toString());

        Address address9 = Address.fromString("QfvF1VkLi6b4Ku9H2p7zVgJ7ZV6vBUR3zH");
        Assertions.assertTrue(address9 instanceof P2SHAddress);
        Assertions.assertEquals("QfvF1VkLi6b4Ku9H2p7zVgJ7ZV6vBUR3zH", address9.toString());

        Address address12 = Address.fromString("tltc1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvseskjtjs8");
        Assertions.assertTrue(address12 instanceof P2TRAddress);
        Assertions.assertEquals("tltc1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvseskjtjs8", address12.toString());
    }

    @Test
    public void validRandomAddressTest() throws InvalidAddressException {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[20];

        for(int i = 0; i < 100; i++) {
            random.nextBytes(values);
            Address address = (i % 2 == 0 ? new P2PKHAddress(values) : new P2WPKHAddress(values));
            String strAddress = address.toString();
            Address checkAddress = Address.fromString(strAddress);
            Assertions.assertArrayEquals(values, checkAddress.getData());
        }

        byte[] values32 = new byte[32];
        for(int i = 0; i < 100; i++) {
            random.nextBytes(values32);
            Address address = new P2WSHAddress(values32);
            String strAddress = address.toString();
            Address checkAddress = Address.fromString(strAddress);
            Assertions.assertArrayEquals(values32, checkAddress.getData());
        }
    }

    @Test
    public void invalidCharacterAddressTest() throws InvalidAddressException {
        Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString("ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4nb"));
    }

    @Test
    public void invalidVersionAddressTest() throws InvalidAddressException {
        Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString("44jnjFM4SbaB7Q8aMtNDG849RQ1gUYgpgo"));
    }

    @Test
    public void invalidChecksumAddressTest() throws InvalidAddressException {
        Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString("34jnjFM4SbaB7Q7aMtNDG849RQ1gUYgpgo"));
    }

    @Test
    public void invalidChecksumAddressTest2() throws InvalidAddressException {
        Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmb3"));
    }

    @Test
    public void bip173InvalidAddressesTest() throws InvalidAddressException {
        List<String> invalidAddresses = Arrays.asList(
                "tc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty", // Invalid human-readable part
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5", // Invalid checksum
                "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2", // Invalid witness version
                "ltc1rw58r3kry", // Invalid program length
                "ltc10w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5m6y25d", // Invalid program length
                "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P", // Invalid program length for witness version 0 (per BIP141)
                "tltc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qsnr4fp", // Mixed case
                "ltc1zw508d6qejxtdg4y5r3zarvaryvqw53wr5", // zero padding of more than 4 bits
                "tltc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3pd9hq5n", // Non-zero padding in 8-to-5 conversion
                "ltc153m7rf" // Empty data section
        );
        for (String address : invalidAddresses) {
            Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString(address));
        }
    }

    @Test
    public void bip350InvalidAddressesTest() throws InvalidAddressException {
        List<String> invalidAddresses = Arrays.asList(
                "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut", // Invalid human-readable part
                "ltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5w2wdg", // Invalid checksum (Bech32 instead of Bech32m)
                "tltc1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqhuhluk", // Invalid checksum (Bech32 instead of Bech32m)
                "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL", // Invalid checksum (Bech32 instead of Bech32m)
                "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7ka8rek8", // Invalid checksum (Bech32m instead of Bech32)
                "tltc1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq4kwe2p", // Invalid checksum (Bech32m instead of Bech32)
                "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4", // Invalid character in checksum
                "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R", // Invalid witness version
                "ltc1pw5kmnaal", // Invalid program length (1 byte)
                "ltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav25f87rvw", // Invalid program length (41 bytes)
                "tltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq2a7uhl", // Mixed case
                "ltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v07q76tu3e", // zero padding of more than 4 bits
                "tltc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vpht2f2d" // Non-zero padding in 8-to-5 conversion
        );
        for (String address : invalidAddresses) {
            Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString(address));
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        Network.set(null);
    }
}