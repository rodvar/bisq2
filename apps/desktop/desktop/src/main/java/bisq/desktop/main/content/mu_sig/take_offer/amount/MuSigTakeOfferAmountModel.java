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

package bisq.desktop.main.content.mu_sig.take_offer.amount;

import bisq.common.monetary.Monetary;
import bisq.desktop.common.view.Model;
import bisq.offer.mu_sig.MuSigOffer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
public class MuSigTakeOfferAmountModel implements Model {
    @Setter
    private MuSigOffer muSigOffer;
    @Setter
    private Monetary sellersReputationBasedQuoteSideAmount;
    @Setter
    private long sellersReputationScore;
    @Setter
    private String amountLimitInfoLink;
    @Setter
    private String linkToWikiText;
    @Setter
    private String headline;
    private final ObjectProperty<Monetary> takersQuoteSideAmount = new SimpleObjectProperty<>();
    private final ObjectProperty<Monetary> takersBaseSideAmount = new SimpleObjectProperty<>();
    private final StringProperty amountLimitInfo = new SimpleStringProperty();
    private final StringProperty amountLimitInfoAmount = new SimpleStringProperty();
    private final StringProperty amountLimitInfoOverlayInfo = new SimpleStringProperty();
    private final BooleanProperty isWarningIconVisible = new SimpleBooleanProperty();
    private final BooleanProperty isAmountHyperLinkDisabled = new SimpleBooleanProperty();
    private final BooleanProperty isAmountLimitInfoOverlayVisible = new SimpleBooleanProperty();
    private final BooleanProperty isAmountLimitInfoVisible = new SimpleBooleanProperty();

    void reset() {
        muSigOffer = null;
        sellersReputationBasedQuoteSideAmount = null;
        sellersReputationScore = 0;
        amountLimitInfoLink = null;
        linkToWikiText = null;
        headline = null;
        takersQuoteSideAmount.set(null);
        takersBaseSideAmount.set(null);
        amountLimitInfo.set(null);
        amountLimitInfoOverlayInfo.set(null);
        isWarningIconVisible.set(false);
        isAmountHyperLinkDisabled.set(false);
        isAmountLimitInfoOverlayVisible.set(false);
        isAmountLimitInfoVisible.set(false);
    }
}