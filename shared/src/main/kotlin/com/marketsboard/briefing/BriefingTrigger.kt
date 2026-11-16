package com.marketsboard.briefing

/** Where a request for a briefing came from. */
enum class TriggerSource {
    /** The on-screen "Brief me" / Stop control (works in Normal and Glance). */
    Button,

    /** The media play/pause or headset hook key, delivered to the foreground Activity. Needs the Settings switch. */
    MediaKey,

    /**
     * Future hook: a steering wheel button. Truck Dash reads the low-speed body bus; when that app (or a service) can
     * broadcast an intent for the wheel buttons, a receiver in this app calls [BriefingTrigger.trigger] with this source.
     * Nothing sends it yet; it follows the same rules as [MediaKey] (the Settings switch).
     */
    SteeringWheel,
}

/**
 * The one way to start or stop a briefing from outside the UI. A key handler, an intent receiver or a future body-bus
 * hook only needs this interface.
 */
fun interface BriefingTrigger {
    /** True when the request was acted on (so a key event can be consumed), false when it was ignored. */
    fun trigger(source: TriggerSource): Boolean
}
