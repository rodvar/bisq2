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

package bisq.desktop.main.content.user;

import bisq.desktop.common.view.TabButton;
import bisq.desktop.main.content.ContentTabView;
import bisq.desktop.navigation.NavigationTarget;
import bisq.i18n.Res;

public class UserView extends ContentTabView<UserModel, UserController> {
    private final TabButton cryptoPaymentAccountsTab;

    public UserView(UserModel model, UserController controller) {
        super(model, controller);

        addTab(Res.get("user.userProfile"), NavigationTarget.USER_PROFILE);
        // We wait for supporting a password protection until there is a wallet integrated
        //addTab(Res.get("user.password"), NavigationTarget.PASSWORD);
        addTab(Res.get("user.fiatPaymentAccounts"), NavigationTarget.FIAT_PAYMENT_ACCOUNTS);

        cryptoPaymentAccountsTab = addTab(Res.get("user.cryptoPaymentAccounts"), NavigationTarget.CRYPTO_CURRENCY_ACCOUNTS);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        cryptoPaymentAccountsTab.setVisible(model.isShowCryptoPaymentAccounts());
        cryptoPaymentAccountsTab.setManaged(model.isShowCryptoPaymentAccounts());
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
    }
}
