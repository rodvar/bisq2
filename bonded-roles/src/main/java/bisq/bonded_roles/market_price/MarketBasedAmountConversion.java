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

package bisq.bonded_roles.market_price;

import bisq.common.market.Market;
import bisq.common.market.MarketRepository;
import bisq.common.monetary.Monetary;

import java.util.Optional;

public class MarketBasedAmountConversion {

    /* --------------------------------------------------------------------- */
    // Bitcoin - Fiat conversions
    /* --------------------------------------------------------------------- */

    public static Optional<Monetary> usdToBtc(MarketPriceService marketPriceService, Monetary usdAmount) {
        Market usdBitcoinMarket = MarketRepository.getUSDBitcoinMarket();
        return fiatToBtc(marketPriceService, usdBitcoinMarket, usdAmount);
    }

    public static Optional<Monetary> fiatToBtc(MarketPriceService marketPriceService,
                                               Market btcFiatMarket,
                                               Monetary fiatAmount) {
        return marketPriceService.findMarketPriceQuote(btcFiatMarket)
                .map(priceQuote -> priceQuote.toBaseSideMonetary(fiatAmount));
    }

    public static Optional<Monetary> btcToUsd(MarketPriceService marketPriceService, Monetary btcAmount) {
        Market usdBitcoinMarket = MarketRepository.getUSDBitcoinMarket();
        return btcToFiat(marketPriceService, usdBitcoinMarket, btcAmount);
    }


    public static Optional<Monetary> btcToFiat(MarketPriceService marketPriceService,
                                               Market btcFiatMarket,
                                               Monetary btcAmount) {
        return marketPriceService.findMarketPriceQuote(btcFiatMarket)
                .map(priceQuote -> priceQuote.toQuoteSideMonetary(btcAmount));
    }


    /* --------------------------------------------------------------------- */
    // USD - Fiat conversions
    /* --------------------------------------------------------------------- */

    // Convert USD to Bitcoin and then back to the Fiat derived from the Fiat market
    public static Optional<Monetary> usdToFiat(MarketPriceService marketPriceService,
                                               Market btcFiatMarket,
                                               Monetary usdAmount) {
        return usdToBtc(marketPriceService, usdAmount).
                flatMap(btc -> btcToFiat(marketPriceService, btcFiatMarket, btc));
    }

    public static Optional<Monetary> fiatToUsd(MarketPriceService marketPriceService,
                                               Market btcFiatMarket,
                                               Monetary fiatAmount) {
        return fiatToBtc(marketPriceService, btcFiatMarket, fiatAmount).
                flatMap(btc -> btcToUsd(marketPriceService, btc));
    }

}
