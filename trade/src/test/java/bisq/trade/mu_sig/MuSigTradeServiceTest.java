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
import bisq.account.accounts.fiat.BankAccountType;
import bisq.account.accounts.fiat.NationalBankAccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.market.Market;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.TransportType;
import bisq.common.network.clear_net_address_types.LocalHostAddressTypeFacade;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.offer.price.spec.PriceSpec;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.settings.SettingsService;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResultAcceptanceMessage;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.MuSigDisputeState;
import bisq.trade.ServiceProvider;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.contact_list.ContactListService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
    void authorizeDisputeCasePaymentDetailsRequest_returnsTrade_whenMediatorMatchesOutsideActiveDisputeState() {
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

        assertThat(authorized).containsSame(trade);
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

    @Test
    void findTradeAndChannelOrQueue_storesMessageUnderTradeId_whenTradeIsMissing() throws Exception {
        MuSigTradeService service = createTradeServiceForPendingQueueTests();
        UserProfile mediator = createUserProfile(1001);
        MuSigMediationStateChangeMessage message = new MuSigMediationStateChangeMessage(
                "id-queue-1",
                "trade-queue-1",
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        Optional<MuSigTrade> result = invokeFindTradeAndChannelOrQueue(service, "trade-queue-1", message);

        assertThat(result).isEmpty();
        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey("trade-queue-1");
        assertThat(getPendingMediationMessagesByTradeId(service).get("trade-queue-1"))
                .contains(message);
    }

    @Test
    void findTradeAndChannelOrQueue_keepsMessagesSeparatedByTradeId() throws Exception {
        MuSigTradeService service = createTradeServiceForPendingQueueTests();
        UserProfile mediator = createUserProfile(1001);
        MuSigMediationStateChangeMessage first = new MuSigMediationStateChangeMessage(
                "id-queue-2a",
                "trade-queue-2a",
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );
        MuSigMediationStateChangeMessage second = new MuSigMediationStateChangeMessage(
                "id-queue-2b",
                "trade-queue-2b",
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        invokeFindTradeAndChannelOrQueue(service, "trade-queue-2a", first);
        invokeFindTradeAndChannelOrQueue(service, "trade-queue-2b", second);

        Map<String, Set<EnvelopePayloadMessage>> pendingMessagesByTradeId = getPendingMediationMessagesByTradeId(service);
        assertThat(pendingMessagesByTradeId).containsOnlyKeys("trade-queue-2a", "trade-queue-2b");
        assertThat(pendingMessagesByTradeId.get("trade-queue-2a")).containsExactly(first);
        assertThat(pendingMessagesByTradeId.get("trade-queue-2b")).containsExactly(second);
    }

    @Test
    void findTradeAndChannelOrQueue_returnsEmptyAndQueuesMessage_whenTradeExistsButChannelIsMissing() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.NO_DISPUTE, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-queue-2c");
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, null);
        MuSigMediationStateChangeMessage message = new MuSigMediationStateChangeMessage(
                "id-queue-2c",
                trade.getId(),
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        Optional<MuSigTrade> result = invokeFindTradeAndChannelOrQueue(service, trade.getId(), message);

        assertThat(result).isEmpty();
        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey(trade.getId());
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(message);
    }

    @Test
    void findTradeAndChannelOrQueue_removesPendingMessage_whenTradeAndChannelAreAvailable() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.NO_DISPUTE, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-queue-3");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, mediator);
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigMediationStateChangeMessage message = new MuSigMediationStateChangeMessage(
                "id-queue-3",
                trade.getId(),
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        getPendingMediationMessagesByTradeId(service).put(trade.getId(), new java.util.concurrent.CopyOnWriteArraySet<>(Set.of(message)));

        Optional<MuSigTrade> result = invokeFindTradeAndChannelOrQueue(service, trade.getId(), message);

        assertThat(result).containsSame(trade);
        assertThat(getPendingMediationMessagesByTradeId(service)).doesNotContainKey(trade.getId());
    }

    @Test
    void processDisputeCasePaymentDetailsRequest_queuesMediatorRequest_whenTradeIsNotYetInMediation() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.NO_DISPUTE, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-payment-1");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, mediator);
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest(trade.getId(), mediator);

        invokeProcessDisputeCasePaymentDetailsRequest(service, message, trade);

        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey(trade.getId());
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(message);
    }

    @Test
    void processDisputeCasePaymentDetailsRequest_queuesArbitratorRequest_whenTradeIsNotYetInArbitration() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile arbitrator = createUserProfile(1005);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.empty(), Optional.of(arbitrator), "trade-payment-2");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, createUserProfile(1001));
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest(trade.getId(), arbitrator);

        invokeProcessDisputeCasePaymentDetailsRequest(service, message, trade);

        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey(trade.getId());
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(message);
    }

    @Test
    void processMediationResultAcceptanceMessage_queuesMessage_whenMediationResultIsMissing() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.empty(), Optional.empty(), "trade-acceptance-1");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, createUserProfile(1001));
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigMediationResultAcceptanceMessage message = new MuSigMediationResultAcceptanceMessage(trade.getId(), peer, true);

        invokeProcessMediationResultAcceptanceMessage(service, message, trade);

        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey(trade.getId());
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(message);
    }

    @Test
    void processDisputeCasePaymentDetailsRequest_doesNotQueueMediatorRequest_whenTradeIsAlreadyInArbitration() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.ARBITRATION_OPEN, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-payment-3");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, mediator);
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigDisputeCasePaymentDetailsRequest message = new MuSigDisputeCasePaymentDetailsRequest(trade.getId(), mediator);

        invokeProcessDisputeCasePaymentDetailsRequest(service, message, trade);

        assertThat(getPendingMediationMessagesByTradeId(service)).doesNotContainKey(trade.getId());
    }

    @Test
    void processMediationStateChangeMessage_queuesReopened_whenTradeIsNotYetClosed() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.MEDIATION_OPEN, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-mediation-1");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, mediator);
        MuSigTradeService service = createTradeServiceForPendingQueueTests(trade, channel);
        MuSigMediationStateChangeMessage message = new MuSigMediationStateChangeMessage(
                "id-reopened-1",
                trade.getId(),
                mediator,
                MediationCaseState.RE_OPENED,
                Optional.empty(),
                Optional.empty()
        );

        invokeProcessMediationStateChangeMessage(service, message, trade);

        assertThat(getPendingMediationMessagesByTradeId(service))
                .containsKey(trade.getId());
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(message);
    }

    @Test
    void maybeProcessPendingMediationMessages_replaysOpenThenPaymentDetailsOnce_andRemovesPendingMessages() throws Exception {
        UserProfile myProfile = createUserProfile(1003);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1001);
        MuSigTrade trade = createTrade(MuSigDisputeState.NO_DISPUTE, myProfile, peer, Optional.of(mediator), Optional.empty(), "trade-replay-1");
        MuSigOpenTradeChannel channel = createOpenTradeChannel(trade, myProfile, peer, mediator);
        MuSigTradeService service = createTradeServiceTestFixture(trade, channel).service();

        MuSigDisputeCasePaymentDetailsRequest paymentDetailsRequest =
                new MuSigDisputeCasePaymentDetailsRequest(trade.getId(), mediator);
        MuSigMediationStateChangeMessage openMessage = new MuSigMediationStateChangeMessage(
                "id-open-replay-1",
                trade.getId(),
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()
        );

        invokeProcessDisputeCasePaymentDetailsRequest(service, paymentDetailsRequest, trade);
        assertThat(getPendingMediationMessagesByTradeId(service).get(trade.getId()))
                .containsExactly(paymentDetailsRequest);

        addPendingMediationMessage(service, trade.getId(), openMessage);

        invokeMaybeProcessPendingMediationMessages(service, trade.getId());
        invokeMaybeProcessPendingMediationMessages(service, trade.getId());

        assertThat(trade.getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_OPEN);
        assertThat(getPendingMediationMessagesByTradeId(service)).doesNotContainKey(trade.getId());
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

    @SuppressWarnings("unchecked")
    private Map<String, Set<EnvelopePayloadMessage>> getPendingMediationMessagesByTradeId(MuSigTradeService service) throws Exception {
        Field field = MuSigTradeService.class.getDeclaredField("pendingMediationMessagesByTradeId");
        field.setAccessible(true);
        return (Map<String, Set<EnvelopePayloadMessage>>) field.get(service);
    }

    private Optional<MuSigTrade> invokeFindTradeAndChannelOrQueue(MuSigTradeService service,
                                                                  String tradeId,
                                                                  EnvelopePayloadMessage message) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("findTradeAndChannelOrQueue", String.class, EnvelopePayloadMessage.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<MuSigTrade> result = (Optional<MuSigTrade>) method.invoke(service, tradeId, message);
        return result;
    }

    private void invokeProcessDisputeCasePaymentDetailsRequest(MuSigTradeService service,
                                                               MuSigDisputeCasePaymentDetailsRequest message,
                                                               MuSigTrade trade) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("processDisputeCasePaymentDetailsRequest",
                MuSigDisputeCasePaymentDetailsRequest.class,
                MuSigTrade.class);
        method.setAccessible(true);
        method.invoke(service, message, trade);
    }

    private void invokeProcessMediationResultAcceptanceMessage(MuSigTradeService service,
                                                               MuSigMediationResultAcceptanceMessage message,
                                                               MuSigTrade trade) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("processMediationResultAcceptanceMessage",
                MuSigMediationResultAcceptanceMessage.class,
                MuSigTrade.class);
        method.setAccessible(true);
        method.invoke(service, message, trade);
    }

    private void invokeProcessMediationStateChangeMessage(MuSigTradeService service,
                                                          MuSigMediationStateChangeMessage message,
                                                          MuSigTrade trade) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("processMediationStateChangeMessage",
                MuSigMediationStateChangeMessage.class,
                MuSigTrade.class);
        method.setAccessible(true);
        method.invoke(service, message, trade);
    }

    private void addPendingMediationMessage(MuSigTradeService service,
                                            String tradeId,
                                            EnvelopePayloadMessage message) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("addPendingMediationMessage", String.class, EnvelopePayloadMessage.class);
        method.setAccessible(true);
        method.invoke(service, tradeId, message);
    }

    private void invokeMaybeProcessPendingMediationMessages(MuSigTradeService service,
                                                            String tradeId) throws Exception {
        Method method = MuSigTradeService.class.getDeclaredMethod("maybeProcessPendingMediationMessages", String.class);
        method.setAccessible(true);
        method.invoke(service, tradeId);
    }

    private MuSigTradeService createTradeServiceForPendingQueueTests() {
        return createTradeServiceTestFixture().service();
    }

    private MuSigTradeService createTradeServiceForPendingQueueTests(MuSigTrade trade, MuSigOpenTradeChannel channel) {
        return createTradeServiceTestFixture(trade, channel).service();
    }

    private TradeServiceTestFixture createTradeServiceTestFixture() {
        return createTradeServiceTestFixture(null, null);
    }

    private TradeServiceTestFixture createTradeServiceTestFixture(MuSigTrade trade, MuSigOpenTradeChannel channel) {
        ServiceProvider serviceProvider = mock(ServiceProvider.class);
        NetworkService networkService = mock(NetworkService.class);
        bisq.identity.IdentityService identityService = mock(bisq.identity.IdentityService.class);
        SettingsService settingsService = mock(SettingsService.class);
        PersistenceService persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigTradeStore> persistence = mock(Persistence.class);
        UserService userService = mock(UserService.class);
        BannedUserService bannedUserService = mock(BannedUserService.class);
        ContactListService contactListService = mock(ContactListService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        UserIdentityService userIdentityService = mock(UserIdentityService.class);
        BondedRolesService bondedRolesService = mock(BondedRolesService.class);
        AlertService alertService = mock(AlertService.class);
        ChatService chatService = mock(ChatService.class);
        MuSigOpenTradeChannelService openTradeChannelService = mock(MuSigOpenTradeChannelService.class);

        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getIdentityService()).thenReturn(identityService);
        when(serviceProvider.getSettingsService()).thenReturn(settingsService);
        when(serviceProvider.getPersistenceService()).thenReturn(persistenceService);
        when(serviceProvider.getUserService()).thenReturn(userService);
        when(serviceProvider.getBondedRolesService()).thenReturn(bondedRolesService);
        when(serviceProvider.getChatService()).thenReturn(chatService);

        when(userService.getBannedUserService()).thenReturn(bannedUserService);
        when(userService.getContactListService()).thenReturn(contactListService);
        when(userService.getUserProfileService()).thenReturn(userProfileService);
        when(userService.getUserIdentityService()).thenReturn(userIdentityService);

        when(bondedRolesService.getAlertService()).thenReturn(alertService);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(openTradeChannelService);

        doReturn(persistence)
                .when(persistenceService)
                .getOrCreatePersistence(any(), any(), any(MuSigTradeStore.class));
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(networkService.getConfidentialMessageServices()).thenReturn(Set.of());
        when(openTradeChannelService.getChannels()).thenReturn(new bisq.common.observable.collection.ObservableSet<>());
        when(openTradeChannelService.findChannelByTradeId(anyString())).thenAnswer(invocation -> {
            String requestedTradeId = invocation.getArgument(0);
            return Optional.ofNullable(channel).filter(value -> value.getTradeId().equals(requestedTradeId));
        });
        when(bannedUserService.isUserProfileBanned(any(UserProfile.class))).thenReturn(false);
        when(bannedUserService.isUserProfileBanned(any(NetworkId.class))).thenReturn(false);
        when(userProfileService.findUserProfile(anyString())).thenAnswer(invocation -> {
            String profileId = invocation.getArgument(0);
            if (trade == null) {
                return Optional.empty();
            }
            if (trade.getMyIdentity().getId().equals(profileId)) {
                return Optional.of(createUserProfile(1999));
            }
            if (trade.getPeer().getNetworkId().getId().equals(profileId)) {
                return Optional.of(trade.getPeer());
            }
            if (trade.getContract().getMediator().map(UserProfile::getId).filter(profileId::equals).isPresent()) {
                return trade.getContract().getMediator();
            }
            if (trade.getContract().getArbitrator().map(UserProfile::getId).filter(profileId::equals).isPresent()) {
                return trade.getContract().getArbitrator();
            }
            return Optional.empty();
        });

        MuSigTradeService service = new MuSigTradeService(new MuSigTradeService.Config("localhost", 9999), serviceProvider, null);
        if (trade != null) {
            service.getTradeById().put(trade.getId(), trade);
        }
        return new TradeServiceTestFixture(service);
    }

    private record TradeServiceTestFixture(MuSigTradeService service) {
    }

    private MuSigOpenTradeChannel createOpenTradeChannel(MuSigTrade trade,
                                                         UserProfile myProfile,
                                                         UserProfile peer,
                                                         UserProfile mediator) {
        UserIdentity myUserIdentity = new UserIdentity(trade.getMyIdentity(), myProfile);
        return MuSigOpenTradeChannel.create(
                trade.getId(),
                myUserIdentity,
                Set.of(peer),
                Optional.of(mediator),
                Optional.empty(),
                MuSigDisputeAgentType.NONE
        );
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
