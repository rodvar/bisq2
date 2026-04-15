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

package bisq.support.arbitration.mu_sig;

import bisq.bonded_roles.BondedRoleType;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRolesService;
import bisq.common.application.Service;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Service used by arbitrators.
 */
@Slf4j
public class MuSigArbitratorService implements Service, ConfidentialMessageService.Listener {
    private final NetworkService networkService;
    private final UserIdentityService userIdentityService;
    private final AuthorizedBondedRolesService authorizedBondedRolesService;
    private final BannedUserService bannedUserService;

    public MuSigArbitratorService(NetworkService networkService,
                                  UserService userService,
                                  BondedRolesService bondedRolesService) {
        this.networkService = networkService;
        userIdentityService = userService.getUserIdentityService();
        bannedUserService = userService.getBannedUserService();
        authorizedBondedRolesService = bondedRolesService.getAuthorizedBondedRolesService();
    }

    /* --------------------------------------------------------------------- */
    // Service
    /* --------------------------------------------------------------------- */

    @Override
    public CompletableFuture<Boolean> initialize() {
        networkService.getConfidentialMessageServices().stream()
                .flatMap(service -> service.getProcessedEnvelopePayloadMessages().stream())
                .forEach(this::onMessage);
        networkService.addConfidentialMessageListener(this);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        networkService.removeConfidentialMessageListener(this);
        return CompletableFuture.completedFuture(true);
    }

    /* --------------------------------------------------------------------- */
    // ConfidentialMessageService.Listener
    /* --------------------------------------------------------------------- */

    @Override
    public void onMessage(EnvelopePayloadMessage envelopePayloadMessage) {
        if (envelopePayloadMessage instanceof MuSigArbitrationRequest message) {
            authorizeArbitrationRequest(message, bannedUserService)
                    .ifPresent(requester -> processArbitrationRequest(message, requester));
        }
    }

    public Optional<UserIdentity> findMyArbitratorUserIdentity(Optional<UserProfile> arbitrator) {
        return findMyArbitratorUserIdentities()
                .filter(userIdentity -> arbitrator.isPresent())
                .filter(userIdentity -> userIdentity.getUserProfile().getId().equals(arbitrator.orElseThrow().getId()))
                .findAny();
    }

    public Stream<UserIdentity> findMyArbitratorUserIdentities() {
        // If we got banned we still want to show the admin UI.
        return authorizedBondedRolesService.getAuthorizedBondedRoleStream(true)
                .filter(data -> data.getBondedRoleType() == BondedRoleType.ARBITRATOR)
                .flatMap(data -> userIdentityService.findUserIdentity(data.getProfileId()).stream());
    }

    static Optional<UserProfile> authorizeArbitrationRequest(MuSigArbitrationRequest message,
                                                             BannedUserService bannedUserService) {
        UserProfile requester = message.getRequester();
        if (bannedUserService.isUserProfileBanned(requester)) {
            log.warn("Ignoring MuSigArbitrationRequest as sender is banned");
            return Optional.empty();
        }
        MuSigContract contract = message.getContract();
        UserProfile peer = message.getPeer();
        if (!hasMatchingContractParties(contract, requester, peer)) {
            log.warn("Ignoring MuSigArbitrationRequest for trade {} because requester {} and peer {} do not match contract parties.",
                    message.getTradeId(), requester.getId(), peer.getId());
            return Optional.empty();
        }
        if (!hasMatchingArbitrator(contract, message.getReceiver())) {
            log.warn("Ignoring MuSigArbitrationRequest for trade {} because arbitrator does not match contract arbitrator.",
                    message.getTradeId());
            return Optional.empty();
        }
        return Optional.of(requester);
    }

    private void processArbitrationRequest(MuSigArbitrationRequest message, UserProfile requester) {
        findMyArbitratorUserIdentity(message.getContract().getArbitrator())
                .ifPresent(myArbitratorUserIdentity -> log.info(
                        "Received MuSigArbitrationRequest for trade {} from requester {} to arbitrator {}.",
                        message.getTradeId(),
                        requester.getId(),
                        myArbitratorUserIdentity.getId()));
    }

    private static boolean hasMatchingContractParties(MuSigContract contract, UserProfile requester, UserProfile peer) {
        return requester.getNetworkId().equals(contract.getMaker().getNetworkId()) &&
                peer.getNetworkId().equals(contract.getTaker().getNetworkId()) ||
                requester.getNetworkId().equals(contract.getTaker().getNetworkId()) &&
                        peer.getNetworkId().equals(contract.getMaker().getNetworkId());
    }

    private static boolean hasMatchingArbitrator(MuSigContract contract, NetworkId arbitratorNetworkId) {
        return contract.getArbitrator()
                .map(UserProfile::getNetworkId)
                .map(contractArbitratorNetworkId -> contractArbitratorNetworkId.equals(arbitratorNetworkId))
                .orElse(false);
    }
}
