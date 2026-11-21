package com.purelock.app.ui.block

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.databinding.ActivityImageCoverBinding
import com.purelock.app.ui.ThemeManager

/**
 * Cover screen raised by the experimental image detection when a frame scores above the
 * sensitivity threshold. It covers whatever was on screen with a plain warning and the way
 * out: go home, call emergency services, or call the trusted contact. Nothing here can trap
 * the user. Back also goes home.
 */
class ImageCoverActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageCoverBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityImageCoverBinding.inflate(layoutInflater)
        setContentView(b.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        b.btnHome.setOnClickListener { goHome() }
        // The dialer only opens the dial pad: the user places the call themselves.
        b.btnEmergencyCall.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (Graph.secure.hasTrustedContact()) {
            val name = Graph.secure.trustedName.ifBlank { Graph.secure.trustedPhone }
            b.btnTrusted.isVisible = true
            b.btnTrusted.text = getString(R.string.block_call_trusted, name)
            b.btnTrusted.setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Graph.secure.trustedPhone}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
