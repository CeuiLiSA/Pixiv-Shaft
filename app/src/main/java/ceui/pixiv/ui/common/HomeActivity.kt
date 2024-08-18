package ceui.pixiv.ui.common

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import ceui.lisa.databinding.ActivityHomeBinding
import ceui.pixiv.session.SessionManager

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            (this as? ComponentActivity)?.enableEdgeToEdge()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SessionManager.isRenewToken.observe(this) {
            binding.renewTokenProgress.isVisible = it
        }
    }
}