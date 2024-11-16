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

package bisq.user.profile;

import bisq.common.encoding.Hex;
import bisq.security.DigestUtil;
import bisq.security.keys.KeyBundleService;
import bisq.security.pow.ProofOfWork;
import bisq.user.UserService;
import bisq.user.identity.NymIdGenerator;
import bisq.user.identity.UserIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;

@Slf4j
@Path("/user-profile")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "User profile API")
public class UserProfileApi {
    public KeyBundleService keyBundleService;
    public UserService userService;

    public UserProfileApi(KeyBundleService keyBundleService, UserService userService) {
        this.keyBundleService = keyBundleService;
        this.userService = userService;
    }

    @Operation(summary = "")
    @ApiResponse(responseCode = "404", description = "")
    @ApiResponse(responseCode = "200", description = "",
            content = {
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserProfilePreparedData.class)
                    )}
    )
    @GET
    @Path("get-user-profile-prepared-data")
    public UserProfilePreparedData getUserProfilePreparedData() {
        KeyPair keyPair = keyBundleService.generateKeyPair();
        byte[] pubKeyHash = DigestUtil.hash(keyPair.getPublic().getEncoded());
        String id = Hex.encode(pubKeyHash);
        ProofOfWork proofOfWork = userService.getUserIdentityService().mintNymProofOfWork(pubKeyHash);
        String nym = NymIdGenerator.generate(pubKeyHash, proofOfWork.getSolution());
        return UserProfilePreparedData.from(keyPair, id, nym, proofOfWork);
    }

    @Operation(summary = "")
    @ApiResponse(responseCode = "404", description = "")
    @ApiResponse(responseCode = "200", description = "",
            content = {
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserProfile.class)
                    )}
    )
    @POST
    @Path("create-and-publish-new-user-profile")
    public UserProfile createAndPublishNewUserProfile(@QueryParam("nick-name") String nickName,
                                                      @QueryParam("terms") @DefaultValue("") String terms,
                                                      @QueryParam("statement") @DefaultValue("") String statement,
                                                      @QueryParam("user-profile-prepared-data") String userProfilePreparedDataJson) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            UserProfilePreparedData userProfilePreparedData = objectMapper.readValue(userProfilePreparedDataJson, UserProfilePreparedData.class);
            KeyPair keyPair = userProfilePreparedData.getKeyPair();
            byte[] pubKeyHash = DigestUtil.hash(keyPair.getPublic().getEncoded());
            ProofOfWork proofOfWork = userProfilePreparedData.getProofOfWork();
            int avatarVersion = 0;
            UserIdentity userIdentity = userService.getUserIdentityService().createAndPublishNewUserProfile(nickName,
                    keyPair,
                    pubKeyHash,
                    proofOfWork,
                    avatarVersion,
                    terms,
                    statement).get();
            return userIdentity.getUserProfile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}