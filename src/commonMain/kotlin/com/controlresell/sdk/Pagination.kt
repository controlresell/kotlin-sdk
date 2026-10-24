package com.controlresell.sdk

import com.controlresell.sdk.generated.models.Account
import com.controlresell.sdk.generated.models.CatalogBrand
import com.controlresell.sdk.generated.models.CatalogCategory
import com.controlresell.sdk.generated.models.CatalogSize
import com.controlresell.sdk.generated.models.CatalogValue
import com.controlresell.sdk.generated.models.Conversation
import com.controlresell.sdk.generated.models.Customer
import com.controlresell.sdk.generated.models.Item
import com.controlresell.sdk.generated.models.Message
import com.controlresell.sdk.generated.models.Order
import com.controlresell.sdk.generated.models.OrderLabel
import com.controlresell.sdk.generated.models.WebhookDelivery
import com.controlresell.sdk.generated.models.WebhookEvent
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

/**
 * Every conversation of the seller, filtered as [ConversationsApi.listConversations][com.controlresell.sdk.generated.apis.ConversationsApi.listConversations]
 * ([from] is inclusive and [to] exclusive, on the last message).
 */
fun ControlResell.listAllConversations(
    unread: Boolean? = null,
    accountId: String? = null,
    from: Instant? = null,
    to: Instant? = null,
    pageSize: Int? = null,
): Flow<Conversation> = paginate { cursor ->
    conversations.listConversations(limit = pageSize, cursor = cursor, unread = unread, accountId = accountId, from = from, to = to)
        .body().let { Page(it.`data`, it.nextCursor) }
}

/** Every message of the conversation [id], over the period [from] (inclusive) to [to] (exclusive) when given. */
fun ControlResell.listAllConversationMessages(
    id: String,
    from: Instant? = null,
    to: Instant? = null,
    pageSize: Int? = null,
): Flow<Message> = paginate { cursor ->
    conversations.listConversationMessages(id = id, limit = pageSize, cursor = cursor, from = from, to = to).body()
        .let { Page(it.`data`, it.nextCursor) }
}

/** Every shipping label still to ship over all the orders, the oldest order first (each names its `orderId`). */
fun ControlResell.listAllLabelsToShip(pageSize: Int? = null): Flow<OrderLabel> = paginate { cursor ->
    orders.listLabelsToShip(limit = pageSize, cursor = cursor).body().let { Page(it.`data`, it.nextCursor) }
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

/**
 * Every customer of the seller, the most recently created first, filtered as
 * [CustomersApi.listCustomers][com.controlresell.sdk.generated.apis.CustomersApi.listCustomers] ([from] is inclusive and
 * [to] exclusive, on the creation date).
 */
fun ControlResell.listAllCustomers(
    accountId: String? = null,
    from: Instant? = null,
    to: Instant? = null,
    pageSize: Int? = null,
): Flow<Customer> = paginate { cursor ->
    customers.listCustomers(limit = pageSize, cursor = cursor, from = from, to = to, accountId = accountId).body()
        .let { Page(it.`data`, it.nextCursor) }
}

/** Every category of the catalog, flat, by identifier; [parentId] keeps the children of one, [platform] one marketplace. */
fun ControlResell.listAllCatalogCategories(
    parentId: Long? = null,
    platform: String? = null,
    pageSize: Int? = null,
): Flow<CatalogCategory> = paginate { cursor ->
    catalogs.listCatalogCategories(limit = pageSize, cursor = cursor, parentId = parentId, platform = platform).body()
        .let { Page(it.`data`, it.nextCursor) }
}

/** Every brand of the catalog, by identifier or, with [q], those whose name starts with it. */
fun ControlResell.listAllCatalogBrands(q: String? = null, pageSize: Int? = null): Flow<CatalogBrand> = paginate { cursor ->
    catalogs.listCatalogBrands(limit = pageSize, cursor = cursor, q = q).body().let { Page(it.`data`, it.nextCursor) }
}

/** Every size of the catalog, each with its size group; [categoryId] keeps those of one category. */
fun ControlResell.listAllCatalogSizes(categoryId: Long? = null, pageSize: Int? = null): Flow<CatalogSize> =
    paginate { cursor ->
        catalogs.listCatalogSizes(limit = pageSize, cursor = cursor, categoryId = categoryId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every one of the colors of the catalog; [categoryId] keeps those of one category. */
fun ControlResell.listAllCatalogColors(categoryId: Long? = null, pageSize: Int? = null): Flow<CatalogValue> =
    paginate { cursor ->
        catalogs.listCatalogColors(limit = pageSize, cursor = cursor, categoryId = categoryId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every one of the conditions of the catalog; [categoryId] keeps those of one category. */
fun ControlResell.listAllCatalogConditions(categoryId: Long? = null, pageSize: Int? = null): Flow<CatalogValue> =
    paginate { cursor ->
        catalogs.listCatalogConditions(limit = pageSize, cursor = cursor, categoryId = categoryId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every one of the package sizes of the catalog; [categoryId] keeps those of one category. */
fun ControlResell.listAllCatalogPackageSizes(categoryId: Long? = null, pageSize: Int? = null): Flow<CatalogValue> =
    paginate { cursor ->
        catalogs.listCatalogPackageSizes(limit = pageSize, cursor = cursor, categoryId = categoryId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/** Every one of the materials of the catalog; [categoryId] keeps those of one category. */
fun ControlResell.listAllCatalogMaterials(categoryId: Long? = null, pageSize: Int? = null): Flow<CatalogValue> =
    paginate { cursor ->
        catalogs.listCatalogMaterials(limit = pageSize, cursor = cursor, categoryId = categoryId).body()
            .let { Page(it.`data`, it.nextCursor) }
    }

/**
 * Every delivery of the webhook endpoint [id] over the last 7 days, the most recent first; [status] is `pending`,
 * `delivered` or `failed`.
 */
fun ControlResell.listAllWebhookDeliveries(
    id: String,
    status: String? = null,
    type: String? = null,
    pageSize: Int? = null,
): Flow<WebhookDelivery> = paginate { cursor ->
    webhooks.listWebhookDeliveries(id = id, status = status, type = type, limit = pageSize, cursor = cursor).body()
        .let { Page(it.`data`, it.nextCursor) }
}

/**
 * Every event of the last 7 days from [since], the oldest first: what a client that was offline catches up. [types]
 * keeps these event types (by default, every type the key's scopes read).
 */
fun ControlResell.listAllWebhookEvents(
    since: Instant? = null,
    types: List<String>? = null,
    pageSize: Int? = null,
): Flow<WebhookEvent> = paginate { cursor ->
    webhooks.listWebhookEvents(
        since = since, type = types?.takeIf { it.isNotEmpty() }?.joinToString(","), limit = pageSize, cursor = cursor,
    ).body().let { Page(it.`data`, it.nextCursor) }
}
