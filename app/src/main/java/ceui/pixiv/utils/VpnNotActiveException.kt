package ceui.pixiv.utils

class VpnNotActiveException :
    RuntimeException("VPN is not active. Please enable VPN and try again.")
