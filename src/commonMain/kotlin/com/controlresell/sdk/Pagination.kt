package com.controlresell.sdk

import com.controlresell.sdk.generated.models.Account
import com.controlresell.sdk.generated.models.Conversation
import com.controlresell.sdk.generated.models.Item
import com.controlresell.sdk.generated.models.Message
import com.controlresell.sdk.generated.models.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/**
 * One page of a list: its elements and the cursor of the next page (`null` on the last one).
 */
data class Page<T>(val data: List<T>, val nextCursor: String?)

/**
 * Every element of a list paginated by cursor, page after page, as the [Flow] is collected. [fetch] gets the cursor of
 * the page to read (`null` for the first one) and must send the same filters each time; the cursor is opaque.
 */
fun <T> paginate(fetch: suspend (cursor: String?) -> Page<T>): Flow<T> = flow {
    var cursor: String? = null
    do {
        val page = fetch(cursor)
        page.data.forEach { emit(it) }
        cursor = page.nextCursor
    } while (cursor != null)
}

/** Every marketplace account of the seller. */
fun ControlResell.listAllAccounts(pageSize: Int? = null): Flow<Account> = paginate { cursor ->
    accounts.listAccounts(limit = pageSize, cursor = cursor).body().let { Page(it.`data`, it.nextCursor) }
}

/** Every item of the seller's stock, filtered as [ItemsApi.listItems][com.controlresell.sdk.generated.apis.ItemsApi.listItems]. */
fun ControlResell.listAllItems(sold: Boolean? = null, accountId: String? = null, pageSize: Int? = null): Flow<Item> =
    paginate { cursor ->
        items.listItems(limit = pageSize, cursor = cursor, sold = sold, accountId = accountId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every conversation of the seller, filtered as [ConversationsApi.listConversations][com.controlresell.sdk.generated.apis.ConversationsApi.listConversations]. */
fun ControlResell.listAllConversations(unread: Boolean? = null, accountId: String? = null, pageSize: Int? = null): Flow<Conversation> =
    paginate { cursor ->
        conversations.listConversations(limit = pageSize, cursor = cursor, unread = unread, accountId = accountId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every message of the conversation [id]. */
fun ControlResell.listAllConversationMessages(id: String, pageSize: Int? = null): Flow<Message> = paginate { cursor ->
    conversations.listConversationMessages(id = id, limit = pageSize, cursor = cursor).body()
        .let { Page(it.`data`, it.nextCursor) }
}

/**
 * Every order of the seller, the most recent first, filtered as
 * [OrdersApi.listOrders][com.controlresell.sdk.generated.apis.OrdersApi.listOrders] (`bucket` is `to_send` or `to_pickup`;
 * [from] is inclusive and [to] exclusive).
 */
fun ControlResell.listAllOrders(
    bucket: String? = null,
    platform: String? = null,
    accountId: String? = null,
    from: Instant? = null,
    to: Instant? = null,
    pageSize: Int? = null,
): Flow<Order> = paginate { cursor ->
    orders.listOrders(
        limit = pageSize, cursor = cursor, bucket = bucket, platform = platform, accountId = accountId, from = from, to = to,
    ).body().let { Page(it.`data`, it.nextCursor) }
}
