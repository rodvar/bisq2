package bisq.wallets.core;

import lombok.Getter;

@Getter
public class WalletException extends Exception {
    public WalletException() {
        super("Wallet Exception");
    }

    public WalletException(String message) {
        super(message);
    }
}

