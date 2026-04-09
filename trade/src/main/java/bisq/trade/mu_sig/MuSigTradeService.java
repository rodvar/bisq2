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

import bisq.account.accounts.Account;
import bisq.account.accounts.AccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.bonded_roles.security_manager.alert.AlertType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.application.ApplicationVersion;
import bisq.common.application.Service;
import bisq.common.monetary.Monetary;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.common.observable.map.ObservableHashMap;
import bisq.common.platform.Version;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.Scheduler;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.offer.Direction;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.offer.price.spec.PriceSpec;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.RateLimitedPersistenceClient;
import bisq.settings.SettingsService;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultAcceptanceMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.MuSigDisputeState;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.events.MuSigTradeEvent;
import bisq.trade.mu_sig.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.mediation.MuSigTraderMediationService;
import bisq.trade.mu_sig.messages.grpc.TxConfirmationStatus;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.protocol.MuSigBuyerAsMakerProtocol;
import bisq.trade.mu_sig.protocol.MuSigBuyerAsTakerProtocol;
import bisq.trade.mu_sig.protocol.MuSigProtocol;
import bisq.trade.mu_sig.protocol.MuSigSellerAsMakerProtocol;
import bisq.trade.mu_sig.protocol.MuSigSellerAsTakerProtocol;
import bisq.trade.protobuf.MusigGrpc;
import bisq.trade.protobuf.SubscribeTxConfirmationStatusRequest;
import bisq.user.banned.BannedUserService;
import bisq.user.contact_list.ContactListService;
import bisq.user.contact_list.ContactReason;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@Getter
public final class MuSigTradeService extends RateLimitedPersistenceClient<MuSigTradeStore> implements Service, ConfidentialMessageService.Listener {
    @Getter
    public static class Config {
        private final String host;
        private final int port;

        public Config(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public static Config from(com.typesafe.config.Config config) {
            com.typesafe.config.Config grpcServer = config.getConfig("grpcServer");
            return new Config(grpcServer.getString("host"),
                    grpcServer.getInt("port"));
        }
    }

    private final ServiceProvider serviceProvider;
    private final NetworkService networkService;
    private final IdentityService identityService;
    private final SettingsService settingsService;
    private final BannedUserService bannedUserService;
    private final AlertService alertService;
    private final MuSigOpenTradeChannelService muSigOpenTradeChannelService;
    private final ContactListService contactListService;
    private final UserProfileService userProfileService;

    private final MuSigTraderMediationService muSigTraderMediationService;

    @Getter
    private final MuSigTradeStore persistableStore = new MuSigTradeStore();
    @Getter
    private final Persistence<MuSigTradeStore> persistence;
    @Getter
    private final MusigGrpcClient musigGrpcClient;
    private final AppType appType;

    // We don't persist the protocol, only the model.
    private final Map<String, MuSigProtocol> tradeProtocolById = new ConcurrentHashMap<>();

    private boolean haltTrading;
    private boolean requireVersionForTrading;
    private Optional<String> minRequiredVersionForTrading = Optional.empty();

    private Pin authorizedAlertDataSetPin, numDaysAfterRedactingTradeDataPin;
    private Scheduler numDaysAfterRedactingTradeDataScheduler;
    private final Set<MuSigTradeMessage> pendingTradeMessages = new CopyOnWriteArraySet<>();
    private final Set<EnvelopePayloadMessage> pendingMediationMessages = new CopyOnWriteArraySet<>();
    private final Map<String, Scheduler> closeTradeTimeoutSchedulerByTradeId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> observeDepositTxConfirmationStatusFutureByTradeId = new ConcurrentHashMap<>();

    private ExecutorService executor;

    public MuSigTradeService(Config config, ServiceProvider serviceProvider, AppType appType) {
        this.serviceProvider = serviceProvider;
        networkService = serviceProvider.getNetworkService();
        identityService = serviceProvider.getIdentityService();
        settingsService = serviceProvider.getSettingsService();
        bannedUserService = serviceProvider.getUserService().getBannedUserService();
        alertService = serviceProvider.getBondedRolesService().getAlertService();
        muSigOpenTradeChannelService = serviceProvider.getChatService().getMuSigOpenTradeChannelService();
        contactListService = serviceProvider.getUserService().getContactListService();
        userProfileService = serviceProvider.getUserService().getUserProfileService();

        muSigTraderMediationService = new MuSigTraderMediationService(networkService,
                serviceProvider.getChatService(),
                serviceProvider.getUserService(),
                serviceProvider.getBondedRolesService());

        persistence = serviceProvider.getPersistenceService().getOrCreatePersistence(this, DbSubDirectory.PRIVATE, persistableStore);

        musigGrpcClient = new MusigGrpcClient(config.getHost(), config.getPort());
        this.appType = appType;
    }


    /* --------------------------------------------------------------------- */
    // Service
    /* --------------------------------------------------------------------- */

    public CompletableFuture<Boolean> initialize() {
        log.info("initialize");

        executor = ExecutorFactory.boundedCachedPool("MuSigTradeService");

        return musigGrpcClient.initialize()
                .thenApply(result -> {
                    persistableStore.getTrades().forEach(this::createAndAddTradeProtocol);

                    networkService.getConfidentialMessageServices().stream()
                            .flatMap(service -> service.getProcessedEnvelopePayloadMessages().stream())
                            .forEach(this::onMessage);
                    networkService.addConfidentialMessageListener(this);

                    // At startup we observe all unconfirmed deposit txs
                    getTrades().stream()
                            .filter(MuSigTrade::isDepositTxCreatedButNotConfirmed)
                            .forEach(this::observeDepositTxConfirmationStatus);

                    authorizedAlertDataSetPin = alertService.getAuthorizedAlertDataSet().addObserver(new CollectionObserver<>() {
                        @Override
                        public void onAdded(AuthorizedAlertData authorizedAlertData) {
                            if (authorizedAlertData.getAlertType() == AlertType.EMERGENCY && authorizedAlertData.getAppType() == appType) {
                                if (authorizedAlertData.isHaltTrading()) {
                                    haltTrading = true;
                                }
                                if (authorizedAlertData.isRequireVersionForTrading()) {
                                    requireVersionForTrading = true;
                                    minRequiredVersionForTrading = authorizedAlertData.getMinVersion();
                                }
                            }
                        }

                        @Override
                        public void onRemoved(Object element) {
                            if (element instanceof AuthorizedAlertData authorizedAlertData) {
                                if (authorizedAlertData.getAlertType() == AlertType.EMERGENCY && authorizedAlertData.getAppType() == appType) {
                                    if (authorizedAlertData.isHaltTrading()) {
                                        haltTrading = false;
                                    }
                                    if (authorizedAlertData.isRequireVersionForTrading()) {
                                        requireVersionForTrading = false;
                                        minRequiredVersionForTrading = Optional.empty();
                                    }
                                }
                            }
                        }

                        @Override
                        public void onCleared() {
                            haltTrading = false;
                            requireVersionForTrading = false;
                            minRequiredVersionForTrading = Optional.empty();
                        }
                    });

                    numDaysAfterRedactingTradeDataScheduler = Scheduler.run(this::maybeRedactDataOfCompletedTrades)
                            .host(this)
                            .periodically(1, TimeUnit.HOURS);
                    numDaysAfterRedactingTradeDataPin = settingsService.getNumDaysAfterRedactingTradeData().addObserver(numDays -> maybeRedactDataOfCompletedTrades());
                    return true;
                });
    }

    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        if (authorizedAlertDataSetPin != null) {
            authorizedAlertDataSetPin.unbind();
            authorizedAlertDataSetPin = null;
        }
        if (numDaysAfterRedactingTradeDataPin != null) {
            numDaysAfterRedactingTradeDataPin.unbind();
            numDaysAfterRedactingTradeDataPin = null;
        }
        if (numDaysAfterRedactingTradeDataScheduler != null) {
            numDaysAfterRedactingTradeDataScheduler.stop();
            numDaysAfterRedactingTradeDataScheduler = null;
        }

        observeDepositTxConfirmationStatusFutureByTradeId.values().forEach(future -> future.cancel(true));
        observeDepositTxConfirmationStatusFutureByTradeId.clear();

        networkService.removeConfidentialMessageListener(this);

        closeTradeTimeoutSchedulerByTradeId.values().forEach(Scheduler::stop);
        closeTradeTimeoutSchedulerByTradeId.clear();

        tradeProtocolById.clear();
        pendingTradeMessages.clear();
        pendingMediationMessages.clear();

        ExecutorFactory.shutdownAndAwaitTermination(executor, 100);
        executor = null;

        return musigGrpcClient.shutdown();
    }


    /* --------------------------------------------------------------------- */
    // ConfidentialMessageService.Listener
    /* --------------------------------------------------------------------- */

    @Override
    public void onMessage(EnvelopePayloadMessage envelopePayloadMessage) {
        if (envelopePayloadMessage instanceof MuSigTradeMessage muSigTradeMessage) {
            verifyTradingNotOnHalt();
            verifyMinVersionForTrading();

            if (bannedUserService.isUserProfileBanned(muSigTradeMessage.getSender())) {
                log.warn("Message ignored as sender is banned");
                return;
            }

            if (muSigTradeMessage instanceof SetupTradeMessage_A) {
                handleMuSigTakeOfferMessage((SetupTradeMessage_A) muSigTradeMessage);
            } else {
                handleMuSigTradeMessage(muSigTradeMessage);
            }
        } else if (envelopePayloadMessage instanceof MuSigMediationStateChangeMessage message) {
            findTradeOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(trade -> authorizeMediationStateChangeMessage(message, trade, bannedUserService))
                    .ifPresent(trade -> processMediationStateChangeMessage(message, trade));
        } else if (envelopePayloadMessage instanceof MuSigMediationResultAcceptanceMessage message) {
            findTradeOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(trade -> authorizeMediationResultAcceptanceMessage(message, trade, bannedUserService))
                    .ifPresent(trade -> processMediationResultAcceptanceMessage(message, trade));
        } else if (envelopePayloadMessage instanceof MuSigDisputeCasePaymentDetailsRequest message) {
            findTradeOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(trade -> authorizeDisputeCasePaymentDetailsRequest(message, trade, bannedUserService))
                    .ifPresent(trade -> processDisputeCasePaymentDetailsRequest(message, trade));
        }
    }


    /* --------------------------------------------------------------------- */
    // Message event
    /* --------------------------------------------------------------------- */

    private void handleMuSigTakeOfferMessage(SetupTradeMessage_A message) {
        MuSigContract muSigContract = message.getContract();
        MuSigProtocol protocol = makerCreatesProtocol(muSigContract, message.getSender(), message.getReceiver());
        handleMuSigTradeMessage(message, protocol);
    }

    private void handleMuSigTradeMessage(MuSigTradeMessage message) {
        String tradeId = message.getTradeId();
        findProtocol(tradeId).ifPresentOrElse(protocol -> handleMuSigTradeMessage(message, protocol),
                () -> {
                    log.info("Protocol with tradeId {} not found. We add the message to pendingMessages for " +
                            "re-processing when the next message arrives. message={}", tradeId, message);
                    pendingTradeMessages.add(message);
                });
    }

    private void handleMuSigTradeMessage(MuSigTradeMessage message, MuSigProtocol protocol) {
        try {
            CompletableFuture.runAsync(() -> {
                protocol.handle(message);

                if (pendingTradeMessages.contains(message)) {
                    log.info("We remove message {} from pendingMessages.", message);
                    pendingTradeMessages.remove(message);
                }

                if (!pendingTradeMessages.isEmpty()) {
                    log.info("We have pendingMessages. We try to re-process them now.");
                    pendingTradeMessages.forEach(this::handleMuSigTradeMessage);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            log.error("Executor rejected task at handleMuSigTradeMessage", e);
            throw e;
        }
    }


    /* --------------------------------------------------------------------- */
    // User events
    /* --------------------------------------------------------------------- */

    public void takeOffer(MuSigTrade trade) {
        handleMuSigTradeEvent(trade, new MuSigTakeOfferEvent());
    }

    // TODO just temp for dev
    public void skipWaitForConfirmation(MuSigTrade trade) {
        handleMuSigTradeEvent(trade, new DepositTxConfirmedEvent());
    }

    public void paymentInitiated(MuSigTrade trade) {
        handleMuSigTradeEvent(trade, new PaymentInitiatedEvent());
    }

    public void paymentReceiptConfirmed(MuSigTrade trade) {
        handleMuSigTradeEvent(trade, new PaymentReceiptConfirmedEvent());
    }

    public void closeTrade(MuSigTrade trade) {
        //todo: just temp, we will move it to closed trades in future
        removeTrade(trade);
    }

    public void requestMediation(MuSigTrade trade) {
        checkArgument(!bannedUserService.isUserProfileBanned(trade.getMyIdentity().getNetworkId()));
        if (trade.getContract().getMediator().isPresent()) {
            MuSigDisputeState current = trade.getDisputeState();
            if (current != MuSigDisputeState.NO_DISPUTE) {
                log.warn("Cannot request mediation for trade {} because not in the right state.",
                        trade.getId());
                return;
            }

            trade.setDisputeState(MuSigDisputeState.MEDIATION_REQUESTED);
            persist();

            muSigTraderMediationService.requestMediation(trade);
        }
    }

    public void acceptMediationResult(MuSigTrade trade) {
        checkArgument(trade.getMuSigMediationResult().isPresent());
        if (trade.getMyself().setMediationResultAccepted(true)) {
            persist();
            muSigTraderMediationService.sendMediationResultAcceptanceMessage(trade);
        }
    }

    public void rejectMediationResult(MuSigTrade trade) {
        checkArgument(trade.getMuSigMediationResult().isPresent());
        if (trade.getMyself().setMediationResultAccepted(false)) {
            persist();
            muSigTraderMediationService.sendMediationResultAcceptanceMessage(trade);
        }
    }

    public void removeTrade(MuSigTrade trade) {
        persistableStore.removeTrade(trade.getId());
        tradeProtocolById.remove(trade.getId());
        persist();
    }

    private void handleMuSigTradeEvent(MuSigTrade trade, MuSigTradeEvent event) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        String tradeId = trade.getId();
        findProtocol(tradeId).ifPresentOrElse(protocol -> {
                    try {
                        CompletableFuture.runAsync(() -> protocol.handle(event), executor);
                    } catch (RejectedExecutionException e) {
                        log.error("Executor rejected task at handleMuSigTradeEvent", e);
                        throw e;
                    }
                },
                () -> log.info("Protocol with tradeId {} not found. This is expected if the trade have been closed already", tradeId));
    }


    /* --------------------------------------------------------------------- */
    // Setup
    /* --------------------------------------------------------------------- */

    public MuSigProtocol takerCreatesProtocol(Identity takerIdentity,
                                              MuSigOffer muSigOffer,
                                              Monetary baseSideAmount,
                                              Monetary quoteSideAmount,
                                              PaymentMethodSpec<?> paymentMethodSpec,
                                              AccountPayload<?> takersAccountPayload,
                                              Optional<UserProfile> mediator,
                                              Optional<UserProfile> arbitrator,
                                              PriceSpec priceSpec,
                                              long marketPrice) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();

        NetworkId takerNetworkId = takerIdentity.getNetworkId();
        MuSigContract contract = new MuSigContract(
                System.currentTimeMillis(),
                muSigOffer,
                takerNetworkId,
                baseSideAmount.getValue(),
                quoteSideAmount.getValue(),
                paymentMethodSpec,
                createSaltedAccountPayloadHash(takersAccountPayload, muSigOffer.getId()),
                mediator,
                arbitrator,
                priceSpec,
                marketPrice);
        boolean isBuyer = muSigOffer.getTakersDirection().isBuy();
        NetworkId makerNetworkId = contract.getMaker().getNetworkId();
        MuSigTrade muSigTrade = new MuSigTrade(contract, isBuyer, true, takerIdentity, muSigOffer, takerNetworkId, makerNetworkId);
        checkArgument(findProtocol(muSigTrade.getId()).isEmpty(),
                "We received the MuSigTakeOfferRequest for an already existing protocol");

        muSigTrade.getMyself().setAccountPayload(takersAccountPayload);

        checkArgument(!tradeExists(muSigTrade.getId()), "A trade with that ID exists already");
        persistableStore.addTrade(muSigTrade);
        persist();

        maybeProcessPendingMediationMessages();

        maybeAddPeerToContactList(makerNetworkId.getId(), takerNetworkId.getId());

        return createAndAddTradeProtocol(muSigTrade);
    }

    public void observeDepositTxConfirmationStatus(MuSigTrade trade) {
        // TODO Ignore the mocked confirmations and use the skip button for better control at development
        if (true) {
            return;
        }

        String tradeId = trade.getId();
        if (observeDepositTxConfirmationStatusFutureByTradeId.containsKey(tradeId)) {
            return;
        }

        try {
            // todo we dont want to create a thread for each trade... but lets see how real impl. will look like
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                SubscribeTxConfirmationStatusRequest request = SubscribeTxConfirmationStatusRequest.newBuilder()
                        .setTradeId(tradeId)
                        .build();
                getMusigAsyncStub().subscribeTxConfirmationStatus(request, new StreamObserver<>() {
                    @Override
                    public void onNext(bisq.trade.protobuf.TxConfirmationStatus proto) {
                        TxConfirmationStatus status = TxConfirmationStatus.fromProto(proto);
                        if (status.getNumConfirmations() > 0) {
                            if (trade.isDepositTxCreatedButNotConfirmed()) {
                                handleMuSigTradeEvent(trade, new DepositTxConfirmedEvent());
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
            }, executor);
            observeDepositTxConfirmationStatusFutureByTradeId.put(tradeId, future);
        } catch (RejectedExecutionException e) {
            log.error("Executor rejected task at observeDepositTxConfirmationStatus", e);
            observeDepositTxConfirmationStatusFutureByTradeId.put(tradeId, CompletableFuture.failedFuture(e));
        }
    }

    public void startCloseTradeTimeout(MuSigTrade trade, MuSigTradeEvent event) {
        stopCloseTradeTimeout(trade);
        closeTradeTimeoutSchedulerByTradeId.computeIfAbsent(trade.getId(), key ->
                Scheduler.run(() ->
                                handleMuSigTradeEvent(trade, event))
                        .after(24, TimeUnit.HOURS));
    }

    public void stopCloseTradeTimeout(MuSigTrade trade) {
        String tradeId = trade.getId();
        if (closeTradeTimeoutSchedulerByTradeId.containsKey(tradeId)) {
            closeTradeTimeoutSchedulerByTradeId.get(tradeId).stop();
        }
    }


    /* --------------------------------------------------------------------- */
    // Misc
    /* --------------------------------------------------------------------- */

    public Optional<MuSigOpenTradeChannel> findMuSigOpenTradeChannel(String tradeId) {
        return muSigOpenTradeChannelService.findChannelByTradeId(tradeId);
    }

    public Optional<MuSigProtocol> findProtocol(String id) {
        return Optional.ofNullable(tradeProtocolById.get(id));
    }

    public Optional<MuSigTrade> findTrade(String tradeId) {
        return persistableStore.findTrade(tradeId);
    }

    public boolean tradeExists(String tradeId) {
        return persistableStore.tradeExists(tradeId);
    }

    public boolean wasOfferAlreadyTaken(MuSigOffer muSigOffer, NetworkId takerNetworkId) {
        return getTrades().stream().anyMatch(trade ->
                trade.getOffer().getId().equals(muSigOffer.getId()) &&
                        trade.getTaker().getNetworkId().getId().equals(takerNetworkId.getId())
        );
    }

    public Collection<MuSigTrade> getTrades() {
        return persistableStore.getTrades();
    }

    public ObservableHashMap<String, MuSigTrade> getTradeById() {
        return persistableStore.getTradeById();
    }

    public MusigGrpc.MusigBlockingStub getMusigBlockingStub() {
        return musigGrpcClient.getBlockingStub();
    }

    public MusigGrpc.MusigStub getMusigAsyncStub() {
        return musigGrpcClient.getAsyncStub();
    }

    // The maker has added the salted account id to the AccountOptions.
    // We will use the payment method chosen by the taker to determine which account we had assigned to that offer.
    public Optional<Account<? extends PaymentMethod<?>, ?>> findMyAccount(MuSigTrade trade) {
        MuSigContract contract = trade.getContract();
        MuSigOffer offer = contract.getOffer();
        PaymentMethod<?> selectedPaymentMethod = contract.getNonBtcSidePaymentMethodSpec().getPaymentMethod();
        Set<Account<? extends PaymentMethod<?>, ?>> matchingAccounts = serviceProvider.getAccountService().getAccounts(selectedPaymentMethod);
        Set<AccountOption> accountOptions = OfferOptionUtil.findAccountOptions(offer.getOfferOptions());
        return accountOptions.stream()
                .filter(accountOption -> accountOption.getPaymentMethod().equals(selectedPaymentMethod))
                .map(AccountOption::getSaltedAccountId)
                .flatMap(saltedAccountId -> OfferOptionUtil.findAccountFromSaltedAccountId(matchingAccounts, saltedAccountId, offer.getId()).stream())
                .findAny();
    }

    /* --------------------------------------------------------------------- */
    // TradeProtocol factory
    /* --------------------------------------------------------------------- */

    private MuSigProtocol makerCreatesProtocol(MuSigContract contract, NetworkId sender, NetworkId receiver) {
        // We only create the data required for the protocol creation.
        // Verification will happen in the MuSigTakeOfferRequestHandler
        MuSigOffer offer = contract.getOffer();
        Direction makersDirection = offer.getDirection();
        boolean isBuyer = makersDirection.isBuy();
        Identity myIdentity = identityService.findAnyIdentityByNetworkId(offer.getMakerNetworkId()).orElseThrow();
        MuSigTrade trade = new MuSigTrade(contract, isBuyer, false, myIdentity, offer, sender, receiver);

        AccountPayload<? extends PaymentMethod<?>> accountPayload = findMyAccount(trade).orElseThrow().getAccountPayload();
        trade.getMyself().setAccountPayload(accountPayload);

        String tradeId = trade.getId();
        checkArgument(findProtocol(tradeId).isEmpty(), "We received the MuSigTakeOfferRequest for an already existing protocol");
        checkArgument(!tradeExists(tradeId), "A trade with that ID exists already");
        persistableStore.addTrade(trade);
        persist();

        maybeProcessPendingMediationMessages();

        maybeAddPeerToContactList(sender.getId(), myIdentity.getId());

        return createAndAddTradeProtocol(trade);
    }

    private MuSigProtocol createAndAddTradeProtocol(MuSigTrade trade) {
        String id = trade.getId();
        MuSigProtocol tradeProtocol;
        boolean isBuyer = trade.isBuyer();
        if (trade.isTaker()) {
            if (isBuyer) {
                tradeProtocol = new MuSigBuyerAsTakerProtocol(serviceProvider, trade);
            } else {
                tradeProtocol = new MuSigSellerAsTakerProtocol(serviceProvider, trade);
            }
        } else {
            if (isBuyer) {
                tradeProtocol = new MuSigBuyerAsMakerProtocol(serviceProvider, trade);
            } else {
                tradeProtocol = new MuSigSellerAsMakerProtocol(serviceProvider, trade);
            }
        }
        trade.setProtocolVersion(tradeProtocol.getVersion());
        tradeProtocolById.put(id, tradeProtocol);
        return tradeProtocol;
    }

    private void verifyTradingNotOnHalt() {
        checkArgument(!haltTrading, "Trading is on halt for security reasons. " +
                "The Bisq security manager has published an emergency alert with haltTrading set to true");
    }

    private void verifyMinVersionForTrading() {
        if (requireVersionForTrading && minRequiredVersionForTrading.isPresent()) {
            checkArgument(ApplicationVersion.getVersion().aboveOrEqual(new Version(minRequiredVersionForTrading.get())),
                    "For trading you need to have version " + minRequiredVersionForTrading.get() + " installed. " +
                            "The Bisq security manager has published an emergency alert with a min. version required for trading.");
        }
    }


    /* --------------------------------------------------------------------- */
    // Redact sensible data
    /* --------------------------------------------------------------------- */

    private void maybeRedactDataOfCompletedTrades() {
        int numDays = settingsService.getNumDaysAfterRedactingTradeData().get();
        long redactDate = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(numDays);
        // Trades which ended up with a failure or got stuck will never get the completed date set.
        // We use a more constrained duration of 45-90 days.
        int numDaysForNotCompletedTrades = Math.max(45, Math.min(90, numDays));
        long redactDateForNotCompletedTrades = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(numDaysForNotCompletedTrades);
        long numChanges = getTrades().stream()
                .filter(trade -> {
                    boolean doRedaction = trade.getTradeCompletedDate().map(date -> date < redactDate)
                            .orElseGet(() -> trade.getContract().getTakeOfferDate() < redactDateForNotCompletedTrades);
                    //todo
                    return doRedaction;
                })
                .count();
        if (numChanges > 0) {
            persist();
        }
    }


    /* --------------------------------------------------------------------- */
    // Misc
    /* --------------------------------------------------------------------- */

    private void maybeAddPeerToContactList(String peersProfileId, String myProfileId) {
        if (settingsService.getDoAutoAddToContactList()) {
            Optional<UserProfile> peersProfile = userProfileService.findUserProfile(peersProfileId);
            Optional<UserProfile> myProfile = userProfileService.findUserProfile(myProfileId);
            if (peersProfile.isPresent() && myProfile.isPresent()) {
                contactListService.addContactListEntry(peersProfile.get(), myProfile.get(), ContactReason.MUSIG_TRADE);
            }
        }
    }

    static Optional<MuSigTrade> authorizeMediationStateChangeMessage(MuSigMediationStateChangeMessage message,
                                                                     MuSigTrade trade,
                                                                     BannedUserService bannedUserService) {
        Optional<UserProfile> mediator = trade.getContract().getMediator();
        if (mediator.isEmpty()) {
            log.warn("Ignoring MuSigMediationStateChangeMessage for trade {} because mediator is missing in contract.",
                    message.getTradeId());
            return Optional.empty();
        }
        if (!mediator.orElseThrow().getId().equals(message.getSenderUserProfile().getId())) {
            log.warn("Ignoring MuSigMediationStateChangeMessage for trade {} with unexpected senderUserProfile {}.",
                    message.getTradeId(), message.getSenderUserProfile());
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(message.getSenderUserProfile())) {
            log.warn("Ignoring MuSigMediationStateChangeMessage as sender is banned");
            return Optional.empty();
        }
        return Optional.of(trade);
    }

    private void processMediationStateChangeMessage(MuSigMediationStateChangeMessage message, MuSigTrade trade) {
        MuSigDisputeState current = trade.getDisputeState();
        if (current == MuSigDisputeState.ARBITRATION_REQUESTED
                || current == MuSigDisputeState.ARBITRATION_OPEN
                || current == MuSigDisputeState.ARBITRATION_CLOSED) {
            return;
        }

        MediationCaseState mediationCaseState = message.getMediationCaseState();
        boolean resultChanged = false;
        boolean shouldSendPeerReport = false;
        MuSigDisputeState next = current;

        if (mediationCaseState == MediationCaseState.OPEN) {
            if (current == MuSigDisputeState.NO_DISPUTE || current == MuSigDisputeState.MEDIATION_REQUESTED) {
                next = MuSigDisputeState.MEDIATION_OPEN;
                shouldSendPeerReport = current == MuSigDisputeState.NO_DISPUTE;
            }
        } else if (mediationCaseState == MediationCaseState.RE_OPENED) {
            next = MuSigDisputeState.MEDIATION_RE_OPENED;
        } else if (mediationCaseState == MediationCaseState.CLOSED) {
            next = MuSigDisputeState.MEDIATION_CLOSED;
            Optional<MuSigMediationResult> incomingResult = message.getMuSigMediationResult();
            if (incomingResult.isEmpty()) {
                log.warn("Ignoring CLOSED MuSigMediationStateChangeMessage without MuSigMediationResult for trade {}.",
                        message.getTradeId());
                return;
            }

            Optional<MuSigMediationResult> currentResult = trade.getMuSigMediationResult();
            if (!isValidMediationResultMessage(trade.getContract(), message)) {
                return;
            }
            if (currentResult.isEmpty()) {
                resultChanged = trade.setMuSigMediationResult(incomingResult.orElseThrow());
            } else if (!currentResult.orElseThrow().equals(incomingResult.orElseThrow())) {
                log.warn("Ignoring changed MuSigMediationResult for trade {} because result cannot be changed once set.",
                        message.getTradeId());
            }
        }

        if (next != current || resultChanged) {
            trade.setDisputeState(next);
            persist();
        }
        muSigTraderMediationService.applyMediationStateToChannel(trade, current);
        if (shouldSendPeerReport) {
            muSigTraderMediationService.sendDisputeCaseDataMessage(trade);
        }
    }

    private boolean isValidMediationResultMessage(MuSigContract contract,
                                                  MuSigMediationStateChangeMessage message) {
        try {
            if (message.getMediationResultSignature().isEmpty()) {
                log.warn("Ignoring MuSigMediationResult for trade {} because mediator signature is missing.",
                        message.getTradeId());
                return false;
            }
            if (!MuSigMediationResultService.verifyMediationResult(message.getMuSigMediationResult().orElseThrow(),
                    message.getMediationResultSignature().orElseThrow(),
                    contract,
                    contract.getMediator().orElseThrow().getPublicKey())) {
                log.warn("Ignoring MuSigMediationResult for trade {} because mediator signature verification failed.",
                        message.getTradeId());
                return false;
            }
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Ignoring MuSigMediationResult for trade {} because mediator signature verification failed.",
                    message.getTradeId(), e);
            return false;
        }
    }

    static Optional<MuSigTrade> authorizeMediationResultAcceptanceMessage(MuSigMediationResultAcceptanceMessage message,
                                                                          MuSigTrade trade,
                                                                          BannedUserService bannedUserService) {
        if (!trade.getPeer().getNetworkId().getId().equals(message.getSenderUserProfile().getId())) {
            log.warn("Ignoring MuSigMediationResultAcceptanceMessage with unexpected senderUserProfile {} for trade {}.",
                    message.getSenderUserProfile(), message.getTradeId());
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(message.getSenderUserProfile())) {
            log.warn("Ignoring MuSigMediationResultAcceptanceMessage as sender is banned");
            return Optional.empty();
        }
        return Optional.of(trade);
    }

    private void processMediationResultAcceptanceMessage(MuSigMediationResultAcceptanceMessage message,
                                                         MuSigTrade trade) {
        if (trade.getMuSigMediationResult().isEmpty()) {
            log.warn("Ignoring MuSigMediationResultAcceptanceMessage for trade {} because MuSigMediationResult is missing.",
                    message.getTradeId());
            return;
        }

        if (trade.getPeer().setMediationResultAccepted(message.isMediationResultAccepted())) {
            persist();
        }
    }

    static Optional<MuSigTrade> authorizeDisputeCasePaymentDetailsRequest(MuSigDisputeCasePaymentDetailsRequest message,
                                                                          MuSigTrade trade,
                                                                          BannedUserService bannedUserService) {
        Optional<UserProfile> disputeRole = findActiveDisputeRole(trade);
        if (disputeRole.isEmpty()) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} with unexpected senderUserProfile {}.",
                    message.getTradeId(), message.getSenderUserProfile());
            return Optional.empty();
        }
        if (!disputeRole.orElseThrow().getId().equals(message.getSenderUserProfile().getId())) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} with unexpected senderUserProfile {}.",
                    message.getTradeId(), message.getSenderUserProfile());
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(message.getSenderUserProfile())) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest as sender is banned");
            return Optional.empty();
        }
        return Optional.of(trade);
    }

    private void processDisputeCasePaymentDetailsRequest(MuSigDisputeCasePaymentDetailsRequest message,
                                                         MuSigTrade trade) {
        if (trade.getMyself().getAccountPayload().isEmpty() || trade.getPeer().getAccountPayload().isEmpty()) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} because account payloads are incomplete.",
                    message.getTradeId());
            return;
        }
        muSigTraderMediationService.sendDisputeCasePaymentDetailsResponse(trade, message.getSenderUserProfile());
    }

    static Optional<UserProfile> findActiveDisputeRole(MuSigTrade trade) {
        MuSigDisputeState disputeState = trade.getDisputeState();
        if (disputeState == MuSigDisputeState.MEDIATION_REQUESTED ||
                disputeState == MuSigDisputeState.MEDIATION_OPEN ||
                disputeState == MuSigDisputeState.MEDIATION_CLOSED ||
                disputeState == MuSigDisputeState.MEDIATION_RE_OPENED) {
            return trade.getContract().getMediator();
        }
        if (disputeState == MuSigDisputeState.ARBITRATION_REQUESTED ||
                disputeState == MuSigDisputeState.ARBITRATION_OPEN ||
                disputeState == MuSigDisputeState.ARBITRATION_CLOSED) {
            return trade.getContract().getArbitrator();
        }
        return Optional.empty();
    }

    private Optional<MuSigTrade> findTradeOrQueue(String tradeId, EnvelopePayloadMessage message) {
        Optional<MuSigTrade> trade = findTrade(tradeId);
        if (trade.isEmpty()) {
            pendingMediationMessages.add(message);
        } else {
            pendingMediationMessages.remove(message);
        }
        return trade;
    }

    private void maybeProcessPendingMediationMessages() {
        pendingMediationMessages.forEach(this::onMessage);
    }
}
