package com.albustech.orbit.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume

/** Waits for a GeckoResult without blocking; null if it fails. */
suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
    accept({ value -> if (cont.isActive) cont.resume(value) }, { if (cont.isActive) cont.resume(null) })
}
