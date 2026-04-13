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

package bisq.trade.mu_sig.mediation;

import bisq.bonded_roles.BondedRoleType;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRole;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRolesService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.contract.ContractService;
import bisq.i18n.Res;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.support.mediation.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResultAcceptanceMessage;
import bisq.trade.MuSigDisputeState;
import bisq.trade.mu_sig.MuSigTrade;
import bisq.user.UserService;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static bisq.support.dispute.DisputeAgentSelection.selectDeterministicProfileId;

/**
 * Service used by traders to select mediators, request mediation and process mediation state changes.
 */
@Slf4j
public class MuSigTraderMediationService {
    private final NetworkService networkService;
    private final UserProfileService userProfileService;
    private final MuSigOpenTradeChannelService muSigOpenTradeChannelService;
    private final AuthorizedBondedRolesService authorizedBondedRolesService;

    public MuSigTraderMediationService(NetworkService networkService,
                                       ChatService chatService,
                                       UserService userService,
                                       BondedRolesService bondedRolesService) {
        this.networkService = networkService;
        userProfileService = userService.getUserProfileService();
        authorizedBondedRolesService = bondedRolesService.getAuthorizedBondedRolesService();
        muSigOpenTradeChannelService = chatService.getMuSigOpenTradeChannelService();
    }

    /* --------------------------------------------------------------------- */
    // API
    /* --------------------------------------------------------------------- */

    public Optional<UserProfile> selectMediator(String makersUserProfileId,
                                                String takersUserProfileId,
                                                String offerId) {
        Set<AuthorizedBondedRole> mediators = authorizedBondedRolesService.getAuthorizedBondedRoleStream()
                .filter(role -> role.getBondedRoleType() == BondedRoleType.MEDIATOR)
                .filter(role -> !role.getProfileId().equals(makersUserProfileId) &&
                        !role.getProfileId().equals(takersUserProfileId))
                .collect(Collectors.toSet());
        return selectMediator(mediators, makersUserProfileId, takersUserProfileId, offerId);
    }

    // This method can be used for verification when taker provides mediators list.
    // If mediator list was not matching the expected one present in the network it might have been a manipulation attempt.
    public Optional<UserProfile> selectMediator(Set<AuthorizedBondedRole> mediators,
                                                String makersProfileId,
                                                String takersProfileId,
                                                String offerId) {
        return selectDeterministicProfileId(mediators, makersProfileId, takersProfileId, offerId)
                .flatMap(userProfileService::findUserProfile);
    }

    public void requestMediation(MuSigTrade trade) {
        MuSigOpenTradeChannel channel = findMuSigOpenTradeChannel(trade.getId()).orElseThrow();
        String encoded = Res.encode("muSig.mediation.requester.tradeLogMessage", channel.getMyUserIdentity().getUserName());
        muSigOpenTradeChannelService.sendTradeLogMessage(encoded, channel);
        muSigOpenTradeChannelService.setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);

        UserProfile peer = userProfileService
                .findUserProfile(trade.getPeer().getNetworkId().getId())
                .orElseThrow();
        UserProfile mediator = trade.getContract().getMediator().orElseThrow();
        NetworkId mediatorNetworkId = mediator.getNetworkId();

        MuSigMediationRequest muSigMediationRequest = new MuSigMediationRequest(trade.getId(),
                trade.getContract(),
                userProfileService.findUserProfile(trade.getMyIdentity().getId()).orElseThrow(),
                peer,
                new ArrayList<>(channel.getChatMessages()),
                mediatorNetworkId);
        networkService.confidentialSend(muSigMediationRequest,
                mediatorNetworkId,
                trade.getMyIdentity().getNetworkIdWithKeyPair());
    }

    public void applyMediationStateToChannel(MuSigTrade trade, MuSigDisputeState previousDisputeState) {
        muSigOpenTradeChannelService
                .findChannelByTradeId(trade.getId())
                .ifPresent(channel ->
                {
                    if (trade.getDisputeState() == MuSigDisputeState.MEDIATION_OPEN) {
                        if (previousDisputeState == MuSigDisputeState.MEDIATION_REQUESTED) {
                            muSigOpenTradeChannelService.addMediationOpenedMessage(channel, Res.encode("authorizedRole.mediator.message.toRequester"));
                        } else if (previousDisputeState == MuSigDisputeState.NO_DISPUTE) {
                            muSigOpenTradeChannelService.setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
                            muSigOpenTradeChannelService.addMediationOpenedMessage(channel, Res.encode("authorizedRole.mediator.message.toNonRequester"));
                        }
                    } else if (trade.getDisputeState() == MuSigDisputeState.MEDIATION_RE_OPENED) {
                        muSigOpenTradeChannelService.setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
                    } else if (trade.getDisputeState() == MuSigDisputeState.MEDIATION_CLOSED) {
                        // Closed mediation case still keeps mediator chat participation active.
                        muSigOpenTradeChannelService.setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
                    }

                });
    }

    public void sendDisputeCaseDataMessage(MuSigTrade trade) {
        Optional<UserProfile> mediator = trade.getContract().getMediator();
        if (mediator.isEmpty()) {
            log.warn("Cannot send MuSigDisputeCaseDataMessage for trade {} because mediator is missing in contract.",
                    trade.getId());
            return;
        }

        MuSigDisputeCaseDataMessage message = new MuSigDisputeCaseDataMessage(
                trade.getId(),
                userProfileService.findUserProfile(trade.getMyIdentity().getId()).orElseThrow(),
                ContractService.getContractHash(trade.getContract()),
                muSigOpenTradeChannelService.findChannelByTradeId(trade.getId())
                        .map(channel -> new ArrayList<>(channel.getChatMessages()))
                        .orElseGet(ArrayList::new)
        );
        networkService.confidentialSend(message,
                mediator.orElseThrow().getNetworkId(),
                trade.getMyIdentity().getNetworkIdWithKeyPair());
    }

    public void sendMediationResultAcceptanceMessage(MuSigTrade trade) {
        boolean mediationResultAccepted = trade.getMyself().getMediationResultAccepted().orElseThrow();
        networkService.confidentialSend(new MuSigMediationResultAcceptanceMessage(trade.getId(),
                        userProfileService.findUserProfile(trade.getMyIdentity().getId()).orElseThrow(),
                        mediationResultAccepted),
                trade.getPeer().getNetworkId(),
                trade.getMyIdentity().getNetworkIdWithKeyPair());

        muSigOpenTradeChannelService.findChannelByTradeId(trade.getId()).ifPresent(channel -> {
            String key = mediationResultAccepted
                    ? "muSig.mediation.result.accepted.tradeLogMessage"
                    : "muSig.mediation.result.rejected.tradeLogMessage";
            String encoded = Res.encode(key, channel.getMyUserIdentity().getUserName());
            muSigOpenTradeChannelService.sendTradeLogMessage(encoded, channel);
        });
    }

    public void sendDisputeCasePaymentDetailsResponse(MuSigTrade trade, UserProfile senderUserProfile) {
        MuSigDisputeCasePaymentDetailsResponse paymentDetailsResponse = new MuSigDisputeCasePaymentDetailsResponse(
                trade.getId(),
                userProfileService.findUserProfile(trade.getMyIdentity().getId()).orElseThrow(),
                trade.getTaker().getAccountPayload().orElseThrow(),
                trade.getMaker().getAccountPayload().orElseThrow()
        );
        networkService.confidentialSend(paymentDetailsResponse,
                senderUserProfile.getNetworkId(),
                trade.getMyIdentity().getNetworkIdWithKeyPair());
    }

    /* --------------------------------------------------------------------- */
    // Private
    /* --------------------------------------------------------------------- */

    private Optional<MuSigOpenTradeChannel> findMuSigOpenTradeChannel(String tradeId) {
        return muSigOpenTradeChannelService.findChannelByTradeId(tradeId);
    }
}
