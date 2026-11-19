package ceui.pixiv.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentDeviceTransferBinding
import ceui.pixiv.session.SessionManager
import ceui.pixiv.transfer.DeviceTransferViewModel.Phase
import ceui.pixiv.transfer.DeviceTransferViewModel.Role
import ceui.pixiv.transfer.DeviceTransferViewModel.State
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.iconTile
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.shape
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.launch

class DeviceTransferFragment : Fragment(R.layout.fragment_device_transfer) {
    private val binding by viewBinding(FragmentDeviceTransferBinding::bind)
    private val model by viewModels<DeviceTransferViewModel>()
    private var camera: BarcodeView? = null
    private var progress: LinearProgressIndicator? = null
    private var progressLabel: TextView? = null
    private var rendered: State? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.scan() else model.scanFailed(R.string.transfer_error_camera_permission)
    }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = model.reset()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupV3Toolbar(view, getString(R.string.transfer_title), binding.scrollView)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        SessionManager.loggedInAccount.observe(viewLifecycleOwner) {
            if (model.state.value.phase == Phase.HOME) render(model.state.value, force = true)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        camera?.resume()
    }

    override fun onPause() {
        camera?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        releaseCamera()
        progress = null
        progressLabel = null
        rendered = null
        super.onDestroyView()
    }

    private fun releaseCamera() {
        camera?.stopDecoding()
        camera?.pause()
        camera = null
    }

    private fun render(state: State, force: Boolean = false) {
        val previous = rendered
        rendered = state
        if (!force && previous?.phase == state.phase && previous.role == state.role &&
            previous.message == state.message && previous.code == state.code &&
            previous.comparison == state.comparison
        ) {
            if (state.phase == Phase.TRANSFER) updateProgress(state.percent)
            return
        }

        backCallback.isEnabled = state.phase != Phase.HOME && state.phase != Phase.COMPLETE
        binding.root.keepScreenOn = state.phase in Phase.SCAN..Phase.RESTORE
        releaseCamera()
        progress = null
        progressLabel = null
        binding.transferContent.removeAllViews()
        binding.scrollView.scrollTo(0, 0)
        val content = binding.transferContent

        when (state.phase) {
            Phase.HOME -> showHome(content)
            Phase.SCAN -> showScanner(content)
            Phase.PAIRING -> {
                val panel = content.panel(R.string.transfer_pair_title, R.string.transfer_pair_body)
                val bitmap = BarcodeEncoder().encodeBitmap(state.code!!.encode(), BarcodeFormat.QR_CODE, 640, 640)
                panel.addView(ImageView(requireContext()).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    maxWidth = context.dp(248)
                    maxHeight = context.dp(248)
                    background = shape(context.dp(12).toFloat(), Color.WHITE)
                    clipToOutline = true
                    contentDescription = getString(R.string.transfer_pair_qr)
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = requireContext().dp(16)
                })
                panel.action(R.string.transfer_cancel, primary = false, action = model::reset)
            }
            Phase.APPROVE -> content.panel(R.string.transfer_approve_title, R.string.transfer_approve_body).apply {
                comparisonCode(checkNotNull(state.comparison))
                action(R.string.transfer_send, action = model::approve)
                action(R.string.transfer_cancel, primary = false, action = model::reset)
            }
            Phase.STARTING -> content.busy(R.string.transfer_connecting, R.string.transfer_keep_open)
            Phase.WAIT -> content.panel(R.string.transfer_wait, R.string.transfer_wait_body).apply {
                comparisonCode(checkNotNull(state.comparison))
                action(R.string.transfer_cancel, primary = false, action = model::reset)
            }
            Phase.PREPARE -> content.busy(R.string.transfer_prepare, R.string.transfer_keep_open)
            Phase.TRANSFER -> {
                val title = if (state.role == Role.SEND) R.string.transfer_sending else R.string.transfer_receiving
                content.busy(title, R.string.transfer_keep_open, percent = state.percent)
            }
            Phase.REMOTE_RESTORE -> content.busy(R.string.transfer_remote_restore, R.string.transfer_keep_open, cancel = false)
            Phase.RESTORE -> content.busy(R.string.transfer_restoring, R.string.transfer_keep_open, cancel = false)
            Phase.COMPLETE -> {
                val body = if (state.role == Role.SEND) R.string.transfer_sent_body else R.string.transfer_restored_body
                content.panel(R.string.transfer_complete, body, icon = R.drawable.ic_check_24dp).apply {
                    action(R.string.transfer_done) { requireActivity().finish() }
                }
            }
            Phase.ERROR -> {
                val statusUnknown = state.message == R.string.transfer_error_status_unknown
                val title = if (statusUnknown) R.string.transfer_status_unknown_title else R.string.transfer_error_title
                val panel = content.panel(title, state.message, icon = R.drawable.ic_error_black_24dp)
                if (statusUnknown) {
                    panel.action(R.string.transfer_done, action = model::reset)
                } else {
                    panel.action(R.string.transfer_retry) {
                        if (state.role == Role.SEND) model.send(SessionManager.loggedInUid) else requestScan()
                    }
                    panel.action(R.string.transfer_back, primary = false, action = model::reset)
                }
            }
        }
    }

    private fun showHome(content: LinearLayout) {
        val uid = SessionManager.loggedInUid
        val intro = content.panel(R.string.transfer_home_title, R.string.transfer_home_body, hero = true)
        if (uid <= 0L || !SessionManager.isLoggedIn) {
            intro.body(R.string.transfer_login_body, 12)
            intro.action(R.string.transfer_login) {
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key)
                })
            }
        } else {
            intro.action(R.string.transfer_receive, action = ::requestScan)
            intro.action(R.string.transfer_old_phone, primary = false) { model.send(uid) }
        }

        content.panel(R.string.transfer_contents_title, R.string.transfer_contents_body, icon = null)
    }

    private fun requestScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            model.scan()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showScanner(content: LinearLayout) {
        val panel = content.panel(R.string.transfer_scan_title, R.string.transfer_scan_body)
        val context = requireContext()
        val guide = View(context).apply {
            background = shape(
                context.dp(12).toFloat(), Color.TRANSPARENT, 0xCCFFFFFF.toInt(), context.dp(1),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        val preview = BarcodeView(context).apply {
            val view = this
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() {
                    if (camera !== view) return
                    val frame = framingRect ?: return
                    guide.layoutParams = FrameLayout.LayoutParams(frame.width(), frame.height()).apply {
                        gravity = Gravity.TOP or Gravity.LEFT
                        leftMargin = frame.left
                        topMargin = frame.top
                    }
                    guide.visibility = View.VISIBLE
                }
                override fun previewStarted() = Unit
                override fun previewStopped() = Unit
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception) {
                    if (camera === view && model.state.value.phase == Phase.SCAN) {
                        model.scanFailed(R.string.transfer_error_camera)
                    }
                }
            })
            decodeSingle { result ->
                if (camera === view) model.receive(result.text.orEmpty(), SessionManager.loggedInUid)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(FrameLayout(context).apply {
            background = shape(context.dp(17).toFloat(), Color.BLACK)
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(guide, FrameLayout.LayoutParams(0, 0))
        }, LinearLayout.LayoutParams(-1, context.dp(216)).apply { topMargin = context.dp(16) })
        panel.action(R.string.transfer_cancel, primary = false, action = model::reset)
        camera = preview
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) preview.resume()
    }

    private fun LinearLayout.panel(
        @StringRes title: Int,
        @StringRes description: Int,
        icon: Int? = R.drawable.ic_v3_swap_account_24,
        hero: Boolean = false,
    ): LinearLayout {
        val context = context
        val padding = context.dp(20)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.card(if (hero) 30 else 22)
            setPadding(padding, padding, padding, padding)
            if (icon != null) {
                val tile = if (icon == R.drawable.ic_check_24dp) {
                    val green = context.color(R.color.v3_green)
                    context.iconTile(icon, tint = green, fill = androidx.core.graphics.ColorUtils.setAlphaComponent(green, 26))
                } else {
                    context.iconTile(icon)
                }
                addView(tile, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            }
            addView(context.label(getString(title), if (hero) 26f else 21f, 700).apply {
                lineHeightRatio(1.22f)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { if (icon != null) topMargin = context.dp(14) })
            body(description, 8)
        }
        addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            if (childCount > 0) topMargin = context.dp(14)
        })
        return panel
    }

    private fun LinearLayout.body(@StringRes text: Int, margin: Int) {
        addView(context.label(getString(text), 14f, 400, context.color(R.color.v3_text_2)).apply {
            lineHeightRatio(1.5f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(margin) })
    }

    private fun LinearLayout.action(@StringRes text: Int, primary: Boolean = true, action: () -> Unit) {
        addView(context.pillButton(getString(text), primary, action = action).apply {
            lineHeightRatio(1.35f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(if (primary) 18 else 10) })
    }

    private fun LinearLayout.comparisonCode(code: String) {
        val palette = V3Palette.from(context)
        addView(context.label(code, 28f, 700, palette.textAccent).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            fontFeatureSettings = "tnum"
            lineHeightRatio(1.3f)
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            background = shape(context.dp(17).toFloat(), palette.alpha08)
            contentDescription = getString(R.string.transfer_comparison, code.toList().joinToString(" "))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(16) })
    }

    private fun LinearLayout.busy(@StringRes title: Int, @StringRes body: Int, percent: Int? = null, cancel: Boolean = true) {
        val panel = panel(title, body)
        val palette = V3Palette.from(context)
        val indicator = LinearProgressIndicator(context).apply {
            trackColor = palette.alpha15
            setIndicatorColor(palette.textAccent)
            trackThickness = context.dp(4)
            trackCornerRadius = context.dp(2)
            isIndeterminate = percent == null
        }
        panel.addView(indicator, LinearLayout.LayoutParams(-1, context.dp(12)).apply { topMargin = context.dp(16) })
        if (percent != null) {
            progress = indicator
            progressLabel = context.label("", 14f, 600, context.color(R.color.v3_text_2)).apply {
                fontFeatureSettings = "tnum"
                panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            }
            updateProgress(percent)
        }
        if (cancel) panel.action(R.string.transfer_cancel, primary = false, action = model::reset)
    }

    private fun updateProgress(percent: Int) {
        progress?.setProgressCompat(percent, motionEnabled())
        progressLabel?.text = getString(R.string.transfer_percent, percent)
    }
}
