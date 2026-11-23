package com.kiro.choptorch

/** When the service listens for the gesture. Pure logic, so it can be tested without a phone. */
object ListenPolicy {
    /**
     * @param screenOn the display is on
     * @param keyguardLocked the lock screen is showing (KeyguardManager.isKeyguardLocked)
     * @param onlyLocked the "Only when locked" setting
     * @param screenOffAllowed the "Work with screen off" setting
     */
    fun shouldListen(screenOn: Boolean, keyguardLocked: Boolean, onlyLocked: Boolean, screenOffAllowed: Boolean): Boolean {
        if (!screenOn && !screenOffAllowed) return false
        if (!onlyLocked) return true
        // "Locked" = screen off, or the lock screen is showing. Unlocked and in use = paused.
        return !screenOn || keyguardLocked
    }
}
