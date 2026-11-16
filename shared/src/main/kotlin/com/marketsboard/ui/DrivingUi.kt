package com.marketsboard.ui

/**
 * The one control that works on the gate itself: "I am parked" after a long loss of signal while locked (a parkade), a 3
 * second hold in the UI. The gate only honours it when it is really offered (locked, no fix for 60 s or more).
 */
interface DrivingActions {
    fun onConfirmParked() {}

    companion object { val None: DrivingActions = object : DrivingActions {} }
}
