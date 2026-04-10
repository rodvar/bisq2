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

package bisq.trade.mu_sig;

import bisq.account.accounts.AccountPayload;
import bisq.account.accounts.fiat.NationalBankAccountPayload;
import bisq.account.accounts.fiat.BankAccountType;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.TransportType;
import bisq.common.network.clear_net_address_types.LocalHostAddressTypeFacade;
import bisq.common.market.Market;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.offer.price.spec.PriceSpec;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResultAcceptanceMessage;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.MuSigDisputeState;
import bisq.user.banned.BannedUserService;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MuSigTradeServiceTest {
    @Test
    void authorizeMediationStateChangeMessage_returnsTrade_whenMediatorMatchesAndIsNotBanned() {
        UserProfile mediator = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_REQUESTED, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-1");
        MuSigMediationStateChangeMessage message = new MuSigMediationStateChangeMessage(
                "id-1",
                "trade-1",
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeMediationStateChangeMessage(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).containsSame(trade);
    }

    @Test
    void authorizeMediationResultAcceptanceMessage_returnsEmpty_whenSenderDoesNotMatchPeer() {
        UserProfile peer = createUserProfile(1002);
        UserProfile stranger = createUserProfile(1004);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.empty(), Optional.empty(), "trade-2");
        MuSigMediationResultAcceptanceMessage message = new MuSigMediationResultAcceptanceMessage(
                "trade-2",
                stranger,
                true
        );

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeMediationResultAcceptanceMessage(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).isEmpty();
    }

    @Test
    void authorizeMediationResultAcceptanceMessage_returnsTrade_whenSenderMatchesPeer() {
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.empty(), Optional.empty(), "trade-2b");
        MuSigMediationResultAcceptanceMessage message = new MuSigMediationResultAcceptanceMessage(
                "trade-2b",
                peer,
                true
        );

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeMediationResultAcceptanceMessage(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).containsSame(trade);
    }

    @Test
    void authorizeDisputeCasePaymentDetailsRequest_returnsTrade_whenMediatorMatchesDuringMediation() {
        UserProfile mediator = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-3");
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest("trade-3", mediator);

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeDisputeCasePaymentDetailsRequest(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).containsSame(trade);
    }

    @Test
    void authorizeDisputeCasePaymentDetailsRequest_returnsEmpty_whenSenderDoesNotMatchActiveDisputeRole() {
        UserProfile mediator = createUserProfile(1001);
        UserProfile stranger = createUserProfile(1004);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-3b");
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest("trade-3b", stranger);

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeDisputeCasePaymentDetailsRequest(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).isEmpty();
    }

    @Test
    void authorizeDisputeCasePaymentDetailsRequest_returnsTrade_whenArbitratorMatchesDuringArbitration() {
        UserProfile arbitrator = createUserProfile(1005);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.ARBITRATION_OPEN, myProfile, peer, Optional.empty(), Optional.of(arbitrator), "trade-4");
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest("trade-4", arbitrator);

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeDisputeCasePaymentDetailsRequest(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).containsSame(trade);
    }

    @Test
    void authorizeDisputeCasePaymentDetailsRequest_returnsEmpty_whenNoActiveDisputeRoleIsPresent() {
        UserProfile mediator = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.NO_DISPUTE, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-5");
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest("trade-5", mediator);

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeDisputeCasePaymentDetailsRequest(
                message,
                trade,
                notBannedUserService()
        );

        assertThat(authorized).isEmpty();
    }

    @Test
    void authorizeDisputeCasePaymentDetailsRequest_returnsEmpty_whenSenderIsBanned() {
        UserProfile mediator = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile myProfile = createUserProfile(1003);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-6");
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest("trade-6", mediator);

        Optional<MuSigTrade> authorized = MuSigTradeService.authorizeDisputeCasePaymentDetailsRequest(
                message,
                trade,
                bannedUserService(mediator)
        );

        assertThat(authorized).isEmpty();
    }

    private MuSigTrade createTrade(MuSigDisputeState disputeState,
                                   UserProfile myProfile,
                                   UserProfile peer,
                                   Optional<UserProfile> mediator,
                                   Optional<UserProfile> arbitrator,
                                   String tradeId) {
        Identity myIdentity = createIdentity("identity-" + tradeId, myProfile.getNetworkId());
        AccountPayload<?> takerPayload = createNationalBankPayload("taker-" + tradeId, "DE" + tradeId.substring(tradeId.length() - 1) + "11");
        AccountPayload<?> makerPayload = createNationalBankPayload("maker-" + tradeId, "DE" + tradeId.substring(tradeId.length() - 1) + "22");
        MuSigContract contract = createContractWithMediatorAndArbitrator(myProfile, peer, mediator, arbitrator, "offer-" + tradeId, takerPayload, makerPayload);
        MuSigTrade trade = new MuSigTrade(contract, true, false, myIdentity, contract.getOffer(), peer.getNetworkId(), myProfile.getNetworkId());
        trade.setDisputeState(disputeState);
        return trade;
    }

    private BannedUserService notBannedUserService() {
        BannedUserService bannedUserService = mock(BannedUserService.class);
        when(bannedUserService.isUserProfileBanned(any(UserProfile.class))).thenReturn(false);
        return bannedUserService;
    }

    private BannedUserService bannedUserService(UserProfile bannedUserProfile) {
        BannedUserService bannedUserService = mock(BannedUserService.class);
        when(bannedUserService.isUserProfileBanned(any(UserProfile.class))).thenAnswer(invocation ->
                bannedUserProfile.equals(invocation.getArgument(0)));
        return bannedUserService;
    }

    private Identity createIdentity(String tag, NetworkId networkId) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        KeyBundle keyBundle = new KeyBundle(tag, keyPair, TorKeyGeneration.generateKeyPair(), I2PKeyGeneration.generateKeyPair());
        return new Identity(tag, networkId, keyBundle);
    }

    private UserProfile createUserProfile(int port) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        PubKey pubKey = new PubKey(keyPair.getPublic(), "key-" + port);
        AddressByTransportTypeMap addresses = new AddressByTransportTypeMap(
                Map.of(TransportType.CLEAR, LocalHostAddressTypeFacade.toLocalHostAddress(port))
        );
        NetworkId networkId = new NetworkId(addresses, pubKey);
        ProofOfWork proofOfWork = new ProofOfWork(pubKey.getHash(), 0, null, 1.0, new byte[72], 0);
        return new UserProfile(1, "nick-" + port, proofOfWork, 0, networkId, "", "", "1.0.0");
    }

    private MuSigContract createContractWithMediatorAndArbitrator(UserProfile maker,
                                                                  UserProfile taker,
                                                                  Optional<UserProfile> mediator,
                                                                  Optional<UserProfile> arbitrator,
                                                                  String offerId,
                                                                  AccountPayload<?> takerPayloadForHash,
                                                                  AccountPayload<?> makerPayloadForHash) {
        Market market = new Market("BTC", "EUR", "Bitcoin", "Euro");
        PaymentMethod<?> paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK);
        List<AccountOption> accountOptions = List.of(new AccountOption(
                paymentMethod,
                "0123456789abcdef0123456789abcdef01234567",
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                OfferOptionUtil.createSaltedAccountPayloadHash(makerPayloadForHash, offerId)
        ));
        MuSigOffer offer = new MuSigOffer(
                offerId,
                maker.getNetworkId(),
                Direction.BUY,
                market,
                new BaseSideFixedAmountSpec(100_000L),
                new MarketPriceSpec(),
                List.of(paymentMethod),
                accountOptions,
                "1.0.0"
        );
        PaymentMethodSpec<?> quoteSidePaymentMethodSpec = PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR");
        byte[] takerSaltedAccountPayloadHash = OfferOptionUtil.createSaltedAccountPayloadHash(takerPayloadForHash, offerId);
        return new MuSigContract(
                System.currentTimeMillis(),
                offer,
                taker.getNetworkId(),
                100_000L,
                3_500_000L,
                quoteSidePaymentMethodSpec,
                takerSaltedAccountPayloadHash,
                mediator,
                arbitrator,
                createPriceSpec(),
                0
        );
    }

    private PriceSpec createPriceSpec() {
        return new MarketPriceSpec();
    }

    private AccountPayload<?> createNationalBankPayload(String id, String accountNr) {
        return new NationalBankAccountPayload(
                id,
                "DE",
                "EUR",
                Optional.of("holder-" + id),
                Optional.empty(),
                Optional.of("bank-" + id),
                Optional.empty(),
                Optional.of("branch-" + id),
                accountNr,
                Optional.of(BankAccountType.CHECKING),
                Optional.of("nationalBankId-" + id)
        );
    }

}
