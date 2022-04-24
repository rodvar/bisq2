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

package bisq.desktop.primary.main.content.social.components;

import bisq.application.DefaultApplicationService;
import bisq.desktop.common.observable.FxBindings;
import bisq.i18n.Res;
import bisq.social.chat.ChatService;
import bisq.social.chat.channels.PublicDiscussionChannel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PublicDiscussionChannelSelection extends ChannelSelection {
    private final Controller controller;

    public PublicDiscussionChannelSelection(DefaultApplicationService applicationService) {
        controller = new Controller(applicationService.getChatService());
    }

    public Pane getRoot() {
        return controller.view.getRoot();
    }

    public void deSelectChannel() {
        controller.deSelectChannel();
    }

    protected static class Controller extends bisq.desktop.primary.main.content.social.components.ChannelSelection.Controller {
        private final Model model;
        @Getter
        private final View view;

        protected Controller(ChatService chatService) {
            super(chatService);

            model = new Model();
            view = new View(model, this);
        }

        @Override
        protected ChannelSelection.Model getChannelSelectionModel() {
            return model;
        }

        @Override
        public void onActivate() {
            super.onActivate();

            channelsPin = FxBindings.<PublicDiscussionChannel, ChannelSelection.View.ChannelItem>bind(model.channelItems)
                    .map(ChannelSelection.View.ChannelItem::new)
                    .to(chatService.getPublicDiscussionChannels());

            selectedChannelPin = FxBindings.subscribe(chatService.getSelectedDiscussionChannel(),
                    channel -> {
                        if (channel instanceof PublicDiscussionChannel) {
                            model.selectedChannel.set(new ChannelSelection.View.ChannelItem(channel));
                        }
                    });
        }

        @Override
        protected void onSelected(ChannelSelection.View.ChannelItem channelItem) {
            if (channelItem == null) {
                return;
            }

            chatService.selectDiscussionChannel(channelItem.getChannel());
        }

        public void deSelectChannel() {
            model.selectedChannel.set(null);
        }
    }

    protected static class Model extends bisq.desktop.primary.main.content.social.components.ChannelSelection.Model {
    }

    protected static class View extends bisq.desktop.primary.main.content.social.components.ChannelSelection.View<Model, Controller> {
        protected View(Model model, Controller controller) {
            super(model, controller);
        }

        @Override
        protected String getHeadlineText() {
            return Res.get("social.publicChannels");
        }

        @Override
        protected ListCell<ChannelItem> getListCell() {
            return new ListCell<>() {
                final Label label = new Label();
                final HBox hBox = new HBox();

                {
                    hBox.setSpacing(10);
                    hBox.setAlignment(Pos.CENTER_LEFT);
                    setCursor(Cursor.HAND);
                    setPrefHeight(40);
                    setPadding(new Insets(0, 0, -20, 0));
                }

                @Override
                protected void updateItem(ChannelItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item != null && !empty && item.getChannel() instanceof PublicDiscussionChannel) {
                        hBox.getChildren().clear();
                        label.setText(item.getDisplayString());
                        hBox.getChildren().add(label);
                        setGraphic(hBox);
                    } else {
                        setGraphic(null);
                    }
                }
            };
        }

    }
}