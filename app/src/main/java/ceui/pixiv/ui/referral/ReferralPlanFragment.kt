package ceui.pixiv.ui.referral

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

class ReferralPlanFragment : Fragment() {
    private val model by viewModels<ReferralPlanViewModel>()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ReferralPageView(requireContext(), object : ReferralPageActions {
            override fun back() { requireActivity().onBackPressedDispatcher.onBackPressed() }
            override fun tab(tab: ReferralTab) { model.tab(tab) }
            override fun filter(filter: ReferralFilter) { model.filter(filter) }
            override fun open(kind: ReferralSheetKind, task: ReferralTask?) {
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(SHEET) == null) {
                    ReferralPlanSheet.newInstance(kind, task).show(childFragmentManager, SHEET)
                }
            }
            override fun toggleTheme() {
                val state = model.value
                val dark = ReferralColors(requireContext(), state.darkOverride, state.accentOverride).dark
                model.appearance(!dark, state.accentOverride)
            }
        })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.state.observe(viewLifecycleOwner) { state ->
            (view as ReferralPageView).render(state)
            val dark = ReferralColors(requireContext(), state.darkOverride, state.accentOverride).dark
            WindowCompat.getInsetsController(requireActivity().window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshTime() }
    private companion object { const val SHEET = "referral_sheet" }
}
