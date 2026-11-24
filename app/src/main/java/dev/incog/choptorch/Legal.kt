package dev.incog.choptorch

/** About, privacy policy and licence texts shown in Settings. Kept in sync with LICENSE and PRIVACY.md. */
object Legal {
    const val AUTHOR = "incogcyberpunk"
    const val COPYRIGHT_YEAR = "2026"
    const val RELEASES_URL = "https://github.com/silentincog/ChopTorch/releases"
    const val PRIVACY_EFFECTIVE = "November 23, 2026"

    val MIT_LICENSE = """
MIT License

Copyright (c) $COPYRIGHT_YEAR $AUTHOR

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""".trim()

    /**
     * Joins hard-wrapped lines into paragraphs so licence texts wrap to the screen width.
     * Blank lines stay paragraph breaks; list items and numbered sections start new lines.
     */
    fun reflow(text: String): String = text.trim().split(Regex("\\n\\s*\\n")).joinToString("\n\n") { para ->
        para.lines().map { it.trim() }.filter { it.isNotEmpty() }.fold(StringBuilder()) { sb, line ->
            val startsItem = Regex("^(\\(?[a-z0-9]{1,2}[.)]|[0-9]+\\.|•|-)\\s").containsMatchIn(line)
            if (sb.isNotEmpty()) sb.append(if (startsItem) "\n" else " ")
            sb.append(line)
        }.toString()
    }

    /** A titled block of the privacy policy. */
    class Section(val title: String, val body: String)

    val PRIVACY: List<Section> = listOf(
        Section(
            "In short",
            "Chop Torch does not collect, send or share any data. It has no internet permission, so it " +
                "cannot send anything off your phone. There are no ads, analytics, accounts or tracking.",
        ),
        Section(
            "What it uses on your phone, and why",
            "• Motion sensor (accelerometer): to recognise your gesture.\n" +
                "• Proximity sensor: for pocket guard, to ignore chops in your pocket.\n" +
                "• Flashlight: to switch the torch.\n" +
                "• Vibration: to confirm the torch switched.\n" +
                "• Notifications: Android requires a visible notification for a service that runs in the background.\n" +
                "• Keep awake and start at boot: so the gesture works with the screen off and after a restart.\n" +
                "• Battery optimisation exemption: so Android doesn't stop the service.\n" +
                "• Root (optional): only when you tap Root keep-alive, or Import from Chop Torch 3.x.",
        ),
        Section(
            "What it stores, and where",
            "Your settings, your personal gesture model (short motion recordings of your gestures and of " +
                "everyday movement, used to tell them apart) and the list of what it learned. Everything is " +
                "kept in the app's private storage on your phone. Other apps can't read it. It is deleted " +
                "when you uninstall the app or clear its data. Start learning again and Forget everything " +
                "learned delete parts of it.",
        ),
        Section(
            "Calibration report",
            "Copy report puts a text report on your clipboard, only when you tap it. It contains your " +
                "phone model, sensor details and the force of your recorded gestures. You decide where to " +
                "paste it.",
        ),
        Section(
            "Changes and contact",
            "If this policy changes, the new version ships with the app and in the repository. " +
                "Questions: contact the author, $AUTHOR.",
        ),
    )

    /** Open-source components included in the app. */
    class Component(val name: String, val by: String, val license: String)

    val THIRD_PARTY: List<Component> = listOf(
        Component("Jetpack Compose, Material 3, AndroidX", "The Android Open Source Project", "Apache License 2.0"),
        Component("Material Symbols icons", "Google", "Apache License 2.0"),
        Component("Kotlin standard library", "JetBrains", "Apache License 2.0"),
        Component("MaterialKolor (Material Color Utilities)", "Jordon de Hoog, based on Google's library", "MIT License / Apache License 2.0"),
    )
}
