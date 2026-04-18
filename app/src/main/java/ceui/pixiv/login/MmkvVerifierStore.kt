package ceui.pixiv.login

import com.tencent.mmkv.MMKV

class MmkvVerifierStore(
    private val mmkv: MMKV = MMKV.defaultMMKV(),
) : VerifierStore {

    override fun save(verifier: String) {
        mmkv.encode(KEY, verifier)
    }

    override fun load(): String? = mmkv.decodeString(KEY)

    override fun clear() {
        mmkv.removeValueForKey(KEY)
    }

    companion object {
        private const val KEY = "pixiv_oauth_pkce_verifier"
    }
}
