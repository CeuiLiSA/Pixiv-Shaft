package ceui.pixiv.ui.settings

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellCountryBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

class CountryHolder(val country: Country) : ListItemHolder()

@ItemHolder(CountryHolder::class)
class CountryViewHolder(bd: CellCountryBinding) :
    ListItemViewHolder<CellCountryBinding, CountryHolder>(bd) {

    override fun onBindViewHolder(holder: CountryHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.countryCodeInfo.text =
            holder.country.name + " (${holder.country.nameCode.uppercase()})"
        binding.countryCodeNumber.text = "+" + holder.country.phoneCode
        binding.countryFlag.text = holder.country.flag
        binding.root.setOnClick {
            it.findActionReceiverOrNull<SelectCountryActionReceiver>()
                ?.selectCountry(holder.country)
        }
    }
}

interface SelectCountryActionReceiver {
    fun selectCountry(country: Country)
}