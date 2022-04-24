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

package bisq.social.chat.channels;

import bisq.social.user.ChatUser;

public interface PrivateChannel {
    String CHANNEL_DELIMITER = "@PC@";

    ChatUser getPeer();

    static String createChannelId(String peersProfileId, String myProfileId) {
        if (peersProfileId.compareTo(myProfileId) < 0) {
            return peersProfileId + CHANNEL_DELIMITER + myProfileId;
        } else { // need to have an ordering here, otherwise there would be 2 channelIDs for the same participants
            return myProfileId + CHANNEL_DELIMITER + peersProfileId;
        }
    }
}