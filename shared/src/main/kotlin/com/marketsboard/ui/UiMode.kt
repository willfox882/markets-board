package com.marketsboard.ui

/**
 * How much UI to show. [Glance] is the minimal view (name, price, change; no editor, no detail) that M4 will force
 * while the truck is moving. M2 only provides the mode and the rendering; nothing here detects speed.
 */
enum class UiMode { Normal, Glance }
