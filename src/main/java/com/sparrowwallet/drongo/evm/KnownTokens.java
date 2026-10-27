package com.sparrowwallet.drongo.evm;

import java.util.*;

/**
 * The official token contracts the LitVM tab uses, and the names worth imitating. Anyone can deploy a token with any
 * name and symbol, so a name proves nothing: only the contract address identifies a token. A token that takes a known
 * name (or a native coin's) on any other contract, or spells its name with non-Latin letters that can pass for Latin
 * ones, is flagged as a lookalike.
 */
public final class KnownTokens {
    private KnownTokens() {}

    public record Known(LitVmNetwork network, Erc20Token token) {}

    private static final List<Known> OFFICIAL = List.of(
            new Known(LitVmNetwork.LITEFORGE_TESTNET, UniswapV2Venue.TUSDC),
            new Known(LitVmNetwork.LITEFORGE_TESTNET, new Erc20Token(UniswapV2Venue.WZKLTC, "WzkLTC", "Wrapped zkLTC", 18)),
            new Known(LitVmNetwork.LITEFORGE_TESTNET, JunkcoinBridge.WJKC),
            new Known(LitVmNetwork.SEPOLIA, ZkLtcBridge.ZKLTC_L1),
            new Known(LitVmNetwork.BASE_SEPOLIA, UniswapV3Venue.USDC),
            new Known(LitVmNetwork.BASE_SEPOLIA, new Erc20Token(UniswapV3Venue.WETH, "WETH", "Wrapped Ether", 18)));

    //Symbols and names of widely held coins and tokens, compared after normalising (letters and digits, upper case)
    private static final Set<String> PROTECTED = Set.of(
            "LTC", "LITECOIN", "ZKLTC", "WZKLTC", "WRAPPEDZKLTC", "WLTC", "WRAPPEDLITECOIN",
            "ETH", "ETHER", "ETHEREUM", "WETH", "WRAPPEDETHER",
            "BTC", "BITCOIN", "WBTC", "WRAPPEDBITCOIN",
            "USDC", "USDCOIN", "TUSDC", "TESTUSDC", "USDT", "TETHER", "TETHERUSD", "DAI", "DAISTABLECOIN",
            "JKC", "JUNKCOIN", "WTJKC", "WJKC", "WRAPPEDJUNKCOIN", "DOGE", "DOGECOIN");

    public static List<Known> all() {
        return OFFICIAL;
    }

    public static Optional<Known> official(LitVmNetwork network, EvmAddress contract) {
        return OFFICIAL.stream().filter(k -> k.network() == network && k.token().contract().equals(contract)).findFirst();
    }

    /** Why the token looks like one it is not, or empty when it is official or its name imitates nothing. */
    public static Optional<String> lookalike(LitVmNetwork network, Erc20Token token) {
        if(official(network, token.contract()).isPresent()) {
            return Optional.empty();
        }
        return imitates(token.symbol(), token.name()).map(name -> "This token is called " + name + " but it is not the official "
                + officialName(network, name) + ". Anyone can deploy a token with any name.");
    }

    /** The known name a symbol and name imitate, or empty. Used to refuse creating such a token. */
    public static Optional<String> imitates(String symbol, String name) {
        for(String text : new String[] {symbol, name}) {
            if(text == null) {
                continue;
            }
            if(!text.chars().allMatch(c -> c < 0x80)) {
                return Optional.of("\"" + text + "\" (it uses non-Latin letters, which can pass for another token's name)");
            }
            if(PROTECTED.contains(normalise(text))) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private static String officialName(LitVmNetwork network, String name) {
        String key = normalise(name);
        if(network.getSymbol().equalsIgnoreCase(key)) {
            return network.getSymbol() + ", which is " + network.getDisplayName() + "'s own coin and not a token at all";
        }
        for(Known known : OFFICIAL) {
            if(known.network() == network && (normalise(known.token().symbol()).equals(key) || normalise(known.token().name()).equals(key))) {
                return known.token().symbol() + " on " + network.getDisplayName() + " (" + known.token().contract() + ")";
            }
        }
        return "token by that name";
    }

    static String normalise(String text) {
        StringBuilder sb = new StringBuilder();
        for(char c : text.toCharArray()) {
            if(Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}
