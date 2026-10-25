package com.mhttn.sunplusremote

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

class RemotePage(act: MainActivity) : Page(act) {
    override val title = "Kumanda"
    override val icon = "🎛"

    private fun key(
        text: String,
        cmd: String,
        label: String = text,
        bg: Int = Ui.BTN,
        size: Float = 15f,
        fg: Int = Ui.TEXT,
        bold: Boolean = false,
        repeat: Boolean = false
    ): Button {
        val b = Ui.button(act, text, bg, size, fg, bold)
        b.onTap(repeat) { Box.sendKey(cmd, label) }
        return b
    }

    override fun build(): View {
        val sv = ScrollView(act)
        sv.setBackgroundColor(Ui.BG)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(14))
        sv.addView(col, ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP))

        // ---- 1) numbers (top)
        fun num(n: String, cmd: String) = key(n, cmd, n, Ui.BTN, 22f, Ui.TEXT, true)
        col.addView(Ui.row(act, num("1", Keys.ONE), num("2", Keys.TWO), num("3", Keys.THREE), heightDp = 54))
        col.addView(Ui.row(act, num("4", Keys.FOUR), num("5", Keys.FIVE), num("6", Keys.SIX), heightDp = 54))
        col.addView(Ui.row(act, num("7", Keys.SEVEN), num("8", Keys.EIGHT), num("9", Keys.NINE), heightDp = 54))
        col.addView(
            Ui.row(
                act,
                key("TV/Radyo", Keys.TV_RADIO, "TV/Radyo", Ui.BTN_DARK, 13f),
                num("0", Keys.ZERO),
                key("Geri ↩", Keys.RETURN, "Geri (recall)", Ui.BTN_DARK, 13f),
                heightDp = 54
            )
        )

        // ---- 2) big VOLUME | mute+power | CHANNEL
        val volUp = key("SES  +", Keys.VOLUME_UP, "SES +", Ui.BTN, 22f, Ui.TEXT, true, true)
        val volDown = key("SES  −", Keys.VOLUME_DOWN, "SES −", Ui.BTN, 22f, Ui.TEXT, true, true)
        val chUp = key("KANAL  +", Keys.CHANNEL_UP, "KANAL +", Ui.BTN, 22f, Ui.TEXT, true, true)
        val chDown = key("KANAL  −", Keys.CHANNEL_DOWN, "KANAL −", Ui.BTN, 22f, Ui.TEXT, true, true)
        val mute = key("🔇\nSessiz", Keys.MUTE, "Sessiz", Ui.BTN_DARK, 14f)
        val power = key("⏻\nGüç", Keys.POWER, "Güç", Ui.RED, 14f, Color.WHITE, true)

        val big = LinearLayout(act)
        big.orientation = LinearLayout.HORIZONTAL
        val bigLp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        bigLp.topMargin = Ui.dp(14)
        big.layoutParams = bigLp
        val left = Ui.column(act, volUp, volDown, heightDp = 96, gapDp = 8)
        val mid = Ui.column(act, mute, power, heightDp = 96, gapDp = 8)
        val right = Ui.column(act, chUp, chDown, heightDp = 96, gapDp = 8)
        big.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1.35f))
        val midLp = LinearLayout.LayoutParams(0, Ui.WRAP, 0.9f)
        midLp.leftMargin = Ui.dp(8)
        midLp.rightMargin = Ui.dp(8)
        big.addView(mid, midLp)
        big.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1.35f))
        col.addView(big)

        // ---- 3) direction pad
        col.addView(Ui.section(act, "YÖN / TAMAM"))
        val arrow = 22f
        col.addView(
            Ui.row(
                act,
                Ui.spacer(act),
                key("▲", Keys.UP, "Yukarı", Ui.BTN, arrow, Ui.TEXT, true, true),
                Ui.spacer(act),
                heightDp = 62
            )
        )
        col.addView(
            Ui.row(
                act,
                key("◀", Keys.LEFT, "Sol", Ui.BTN, arrow, Ui.TEXT, true, true),
                key("OK", Keys.ENTER, "OK", Ui.ACCENT, 20f, Color.WHITE, true),
                key("▶", Keys.RIGHT, "Sağ", Ui.BTN, arrow, Ui.TEXT, true, true),
                heightDp = 62
            )
        )
        col.addView(
            Ui.row(
                act,
                Ui.spacer(act),
                key("▼", Keys.DOWN, "Aşağı", Ui.BTN, arrow, Ui.TEXT, true, true),
                Ui.spacer(act),
                heightDp = 62
            )
        )

        // ---- 4) menu keys
        col.addView(
            Ui.row(
                act,
                key("Menü", Keys.MENU),
                key("Çıkış", Keys.EXIT),
                key("Bilgi", Keys.INFO),
                key("EPG", Keys.EPG),
                heightDp = 48
            )
        )
        col.addView(
            Ui.row(
                act,
                key("Fav", Keys.FAV),
                key("Kaynak", Keys.SOURCE),
                key("Dev", Keys.DEV),
                key("Ses/Dil", Keys.AUDIO, "Ses/Dil", Ui.BTN, 13f),
                heightDp = 48
            )
        )
        col.addView(
            Ui.row(
                act,
                key("Altyazı", Keys.SUBTITLE, "Altyazı", Ui.BTN, 13f),
                key("Sayfa ▲", Keys.PAGE_UP, "Sayfa ▲", Ui.BTN, 13f, Ui.TEXT, false, true),
                key("Sayfa ▼", Keys.PAGE_DOWN, "Sayfa ▼", Ui.BTN, 13f, Ui.TEXT, false, true),
                key("Tekrar", Keys.REPEAT),
                heightDp = 48
            )
        )

        // ---- 5) colour keys
        col.addView(Ui.section(act, "RENKLİ TUŞLAR"))
        col.addView(
            Ui.row(
                act,
                key("", Keys.RED, "Kırmızı", Ui.RED),
                key("", Keys.GREEN, "Yeşil", Ui.GREEN),
                key("", Keys.YELLOW, "Sarı", Ui.YELLOW),
                key("", Keys.BLUE, "Mavi", Ui.BLUE),
                heightDp = 40
            )
        )

        // ---- 6) media keys
        col.addView(Ui.section(act, "OYNATMA"))
        col.addView(
            Ui.row(
                act,
                key("⏮", Keys.PREVIOUS, "Önceki"),
                key("⏪", Keys.REWIND, "Geri sar", Ui.BTN, 15f, Ui.TEXT, false, true),
                key("▶", Keys.PLAY, "Oynat", Ui.GREEN, 16f, Color.WHITE),
                key("⏸", Keys.PAUSE, "Duraklat"),
                heightDp = 48
            )
        )
        col.addView(
            Ui.row(
                act,
                key("⏹", Keys.STOP, "Durdur"),
                key("⏩", Keys.FAST_FORWARD, "İleri sar", Ui.BTN, 15f, Ui.TEXT, false, true),
                key("⏭", Keys.NEXT, "Sonraki"),
                key("⏺", Keys.RECORD, "Kayıt", Ui.RED, 16f, Color.WHITE),
                heightDp = 48
            )
        )

        return sv
    }
}
