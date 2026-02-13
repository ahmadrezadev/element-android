/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.app.features.home.room.detail.timeline.helper.MessageItemAttributesFactory
import im.vector.app.features.home.room.detail.timeline.item.ManaCallTileTimelineItem
import im.vector.app.features.home.room.detail.timeline.item.ManaCallTileTimelineItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryEvents
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.ManaCallNotifyContent
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class ManaCallItemFactory @Inject constructor(
        private val session: Session,
        private val userPreferencesProvider: UserPreferencesProvider,
        private val messageColorProvider: MessageColorProvider,
        private val messageInformationDataFactory: MessageInformationDataFactory,
        private val messageItemAttributesFactory: MessageItemAttributesFactory,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val noticeItemFactory: NoticeItemFactory
) {

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        if (event.root.eventId == null) return null
        val showHiddenEvents = userPreferencesProvider.shouldShowHiddenEvents()
        val roomSummary = params.partialState.roomSummary ?: return null
        val informationData = messageInformationDataFactory.create(params)
        val callItem = when (event.root.getClearType()) {
            in EventType.ELEMENT_CALL_NOTIFY.values -> {
                val notifyContent: ManaCallNotifyContent = event.root.content.toModel() ?: return null
                createManaCallTileTimelineItem(
                        roomSummary = roomSummary,
                        callId = notifyContent.callId.orEmpty(),
                        callStatus = ManaCallTileTimelineItem.CallStatus.INVITED,
                        callKind = ManaCallTileTimelineItem.CallKind.VIDEO,
                        callback = params.callback,
                        highlight = params.isHighlighted,
                        informationData = informationData,
                        reactionsSummaryEvents = params.reactionsSummaryEvents
                )
            }
            else -> null
        }
        return if (callItem == null && showHiddenEvents) {
            // Fallback to notice item for showing hidden events
            noticeItemFactory.create(params)
        } else {
            callItem
        }
    }

    private fun createManaCallTileTimelineItem(
            roomSummary: RoomSummary,
            callId: String,
            callKind: ManaCallTileTimelineItem.CallKind,
            callStatus: ManaCallTileTimelineItem.CallStatus,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            reactionsSummaryEvents: ReactionsSummaryEvents?
    ): ManaCallTileTimelineItem? {
        val userOfInterest = roomSummary.toMatrixItem()
        val attributes = messageItemAttributesFactory.create(null, informationData, callback, reactionsSummaryEvents).let {
            ManaCallTileTimelineItem.Attributes(
                    callId = callId,
                    callKind = callKind,
                    callStatus = callStatus,
                    informationData = informationData,
                    avatarRenderer = it.avatarRenderer,
                    messageColorProvider = messageColorProvider,
                    itemClickListener = it.itemClickListener,
                    itemLongClickListener = it.itemLongClickListener,
                    reactionPillCallback = it.reactionPillCallback,
                    readReceiptsCallback = it.readReceiptsCallback,
                    userOfInterest = userOfInterest,
                    callback = callback,
                    reactionsSummaryEvents = reactionsSummaryEvents
            )
        }
        return ManaCallTileTimelineItem_()
                .attributes(attributes)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
    }
}
