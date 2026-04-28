/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.content.authorized_role.arbitrator.mu_sig.components;

import bisq.support.arbitration.ArbitrationPayoutDistributionType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MuSigArbitrationPayoutDistributionCalculatorTest {
    private static final long TRADE_AMOUNT_SATS = 480_000; // 0.0048 BTC
    private static final long BUYER_SECURITY_DEPOSIT_SATS = 120_000; // 25% of trade amount
    private static final long SELLER_SECURITY_DEPOSIT_SATS = 120_000; // 25% of trade amount
    private static final long TOTAL_PAYOUT_SATS = 720_000;

    private static final MuSigArbitrationPayoutDistributionCalculator.PayoutContext CONTEXT =
            new MuSigArbitrationPayoutDistributionCalculator.PayoutContext(
                    TRADE_AMOUNT_SATS,
                    BUYER_SECURITY_DEPOSIT_SATS,
                    SELLER_SECURITY_DEPOSIT_SATS,
                    TOTAL_PAYOUT_SATS);

    @Test
    void calculateForTypeBuyerGetsTradeAmount() {
        MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.calculateForType(
                        ArbitrationPayoutDistributionType.BUYER_GETS_TRADE_AMOUNT,
                        CONTEXT)
                .orElseThrow();

        assertEquals(600_000, payoutAmounts.buyerAmountAsSats());
        assertEquals(0, payoutAmounts.sellerAmountAsSats());
    }

    @Test
    void calculateForTypeSellerGetsTradeAmount() {
        MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.calculateForType(
                        ArbitrationPayoutDistributionType.SELLER_GETS_TRADE_AMOUNT,
                        CONTEXT)
                .orElseThrow();

        assertEquals(0, payoutAmounts.buyerAmountAsSats());
        assertEquals(600_000, payoutAmounts.sellerAmountAsSats());
    }

    @Test
    void calculateForTypeCustomPayoutReturnsEmpty() {
        Optional<MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts> payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.calculateForType(
                ArbitrationPayoutDistributionType.CUSTOM_PAYOUT,
                CONTEXT);

        assertEquals(Optional.empty(), payoutAmounts);
    }

    @Test
    void customPayoutCanLeaveAmountUndistributed() {
        MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.alignCustomPayout(
                        CONTEXT,
                        Optional.of(100_000L),
                        Optional.of(200_000L),
                        true)
                .orElseThrow();

        assertEquals(100_000, payoutAmounts.buyerAmountAsSats());
        assertEquals(200_000, payoutAmounts.sellerAmountAsSats());
    }

    @Test
    void customPayoutBuyerEditedReducesSellerAmountWhenTotalWouldBeExceeded() {
        MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.alignCustomPayout(
                        CONTEXT,
                        Optional.of(600_000L),
                        Optional.of(300_000L),
                        true)
                .orElseThrow();

        assertEquals(600_000, payoutAmounts.buyerAmountAsSats());
        assertEquals(120_000, payoutAmounts.sellerAmountAsSats());
    }

    @Test
    void customPayoutSellerEditedReducesBuyerAmountWhenTotalWouldBeExceeded() {
        MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts payoutAmounts = MuSigArbitrationPayoutDistributionCalculator.alignCustomPayout(
                        CONTEXT,
                        Optional.of(600_000L),
                        Optional.of(300_000L),
                        false)
                .orElseThrow();

        assertEquals(420_000, payoutAmounts.buyerAmountAsSats());
        assertEquals(300_000, payoutAmounts.sellerAmountAsSats());
    }

    @Test
    void customPayoutMissingInputReturnsEmpty() {
        Optional<MuSigArbitrationPayoutDistributionCalculator.PayoutAmounts> payoutAmounts =
                MuSigArbitrationPayoutDistributionCalculator.alignCustomPayout(
                        CONTEXT,
                        Optional.empty(),
                        Optional.of(100_000L),
                        true);

        assertEquals(Optional.empty(), payoutAmounts);
    }
}
