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

import bisq.common.util.MathUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.offer.amount.OfferAmountUtil;
import bisq.offer.options.CollateralOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.support.arbitration.ArbitrationPayoutDistributionType;
import bisq.trade.mu_sig.MuSigTradeUtils;

import java.util.Optional;

final class MuSigArbitrationPayoutDistributionCalculator {
    record PayoutContext(long tradeAmount, long buyerSecurityDeposit, long sellerSecurityDeposit,
                         long totalPayoutAmount) {
    }

    record PayoutAmounts(long buyerAmountAsSats, long sellerAmountAsSats) {
    }

    static Optional<PayoutContext> createPayoutContext(MuSigContract contract) {
        Optional<CollateralOption> collateralOption =
                OfferOptionUtil.findCollateralOption(contract.getOffer().getOfferOptions());
        if (collateralOption.isEmpty()) {
            return Optional.empty();
        }

        long tradeAmount = contract.getBtcSideAmount();
        long buyerSecurityDeposit = OfferAmountUtil.calculateSecurityDepositAsBTC(
                MuSigTradeUtils.getBtcSideMonetary(contract),
                collateralOption.get().getBuyerSecurityDeposit()).getValue();
        long sellerSecurityDeposit = OfferAmountUtil.calculateSecurityDepositAsBTC(
                MuSigTradeUtils.getBtcSideMonetary(contract),
                collateralOption.get().getSellerSecurityDeposit()).getValue();
        long totalPayoutAmount = tradeAmount + buyerSecurityDeposit + sellerSecurityDeposit;
        return Optional.of(new PayoutContext(
                tradeAmount,
                buyerSecurityDeposit,
                sellerSecurityDeposit,
                totalPayoutAmount));
    }

    static Optional<PayoutAmounts> calculateForType(ArbitrationPayoutDistributionType payoutDistributionType,
                                                    PayoutContext context) {
        return switch (payoutDistributionType) {
            case CUSTOM_PAYOUT -> Optional.empty();
            case BUYER_GETS_TRADE_AMOUNT -> Optional.of(new PayoutAmounts(
                    context.tradeAmount() + context.buyerSecurityDeposit(),
                    0));
            case SELLER_GETS_TRADE_AMOUNT -> Optional.of(new PayoutAmounts(
                    0,
                    context.tradeAmount() + context.sellerSecurityDeposit()));
        };
    }

    static Optional<PayoutAmounts> alignCustomPayout(PayoutContext context,
                                                     Optional<Long> buyerPayoutAmountAsSats,
                                                     Optional<Long> sellerPayoutAmountAsSats,
                                                     boolean buyerFieldEdited) {
        if (buyerPayoutAmountAsSats.isEmpty() || sellerPayoutAmountAsSats.isEmpty()) {
            return Optional.empty();
        }

        long buyerAmountAsSats = MathUtils.bounded(0, context.totalPayoutAmount(), buyerPayoutAmountAsSats.get());
        long sellerAmountAsSats = MathUtils.bounded(0, context.totalPayoutAmount(), sellerPayoutAmountAsSats.get());
        long excessAmount = buyerAmountAsSats + sellerAmountAsSats - context.totalPayoutAmount();
        if (excessAmount > 0) {
            if (buyerFieldEdited) {
                sellerAmountAsSats = Math.max(0, sellerAmountAsSats - excessAmount);
            } else {
                buyerAmountAsSats = Math.max(0, buyerAmountAsSats - excessAmount);
            }
        }
        return Optional.of(new PayoutAmounts(buyerAmountAsSats, sellerAmountAsSats));
    }
}
