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

package bisq.trade;

import bisq.account.AccountService;
import bisq.bonded_roles.BondedRolesService;
import bisq.settings.SettingsService;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.mu_sig.DelayedPayoutTxReceiverService;
import bisq.trade.mu_sig.MuSigTradeService;
import bisq.user.UserService;

public interface ServiceProvider {
    bisq.network.NetworkService getNetworkService();

    bisq.identity.IdentityService getIdentityService();

    bisq.persistence.PersistenceService getPersistenceService();

    bisq.offer.OfferService getOfferService();

    bisq.contract.ContractService getContractService();

    bisq.support.SupportService getSupportService();

    bisq.chat.ChatService getChatService();

    BondedRolesService getBondedRolesService();

    UserService getUserService();

    SettingsService getSettingsService();

    BisqEasyTradeService getBisqEasyTradeService();

    MuSigTradeService getMuSigTradeService();

    AccountService getAccountService();

    DelayedPayoutTxReceiverService getDelayedPayoutTxReceiverService();
}
