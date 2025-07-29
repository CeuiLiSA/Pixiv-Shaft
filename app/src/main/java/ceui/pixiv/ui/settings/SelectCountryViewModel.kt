package ceui.pixiv.ui.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.loxia.asLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Locale

class SelectCountryViewModel : ViewModel() {


    private val _dataSet = mutableListOf<Country>()
    private val _displayList = MutableLiveData<List<Country>>()
    private val _defaultCountryCode = MutableLiveData<String>()
    private val _defaultCountryName = MutableLiveData<String>()
    private val _defaultNameCode = MutableLiveData<String>()

    val displayList: LiveData<List<Country>> get() = _displayList.asLiveData()
    val dataSet: List<Country> get() = _dataSet.toList()
    val defaultCountryCode: LiveData<String> get() = _defaultCountryCode.asLiveData()
    val defaultCountryName: LiveData<String> get() = _defaultCountryName.asLiveData()
    val defaultNameCode: LiveData<String> get() = _defaultNameCode.asLiveData()


    fun loadData(
        context: Context,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _dataSet.clear()
            val xmlFactoryObject = XmlPullParserFactory.newInstance()
            val xmlPullParser = xmlFactoryObject.newPullParser()
            val inputStream: InputStream = context.resources.openRawResource(R.raw.ccp_english)

            xmlPullParser.setInput(inputStream, "UTF-8")
            var event: Int = xmlPullParser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val name = xmlPullParser.name
                when (event) {
                    XmlPullParser.START_TAG -> {
                    }

                    XmlPullParser.END_TAG -> {
                        if (name == "country") {
                            val nameCode = xmlPullParser.getAttributeValue(null, "name_code")
                            val phoneCode = xmlPullParser.getAttributeValue(null, "phone_code")
                            val englishName = xmlPullParser.getAttributeValue(null, "english_name")
                            val name = xmlPullParser.getAttributeValue(null, "name")
                            val simpleName = xmlPullParser.getAttributeValue(null, "simple_name")
                            _dataSet.add(
                                Country(
                                    name,
                                    englishName,
                                    nameCode,
                                    phoneCode,
                                    countryCodeToEmojiFlag(nameCode),
                                    simpleName
                                )
                            )
                        }
                    }
                }
                event = xmlPullParser.next()
            }
            _displayList.postValue(_dataSet.toList())


            val suggestedCode = getDefaultNameCode()
            setDefaultCountryInfo(suggestedCode)
        }
    }

    fun getDefaultNameCode(): String {
        // 获取当前手机的区域信息
        val countryCode = Locale.getDefault().country.lowercase() // 获取 ISO 3166-1 alpha-2 国家代码并转为小写
        return if (countryCode.isNotEmpty() == true) {
            countryCode
        } else {
            "cn"
        }
    }

    private fun setDefaultCountryInfo(nameCode: String) {
        val country = _dataSet.firstOrNull {
            it.nameCode.equals(nameCode, true)
        }
        if (country != null) {
            _defaultCountryCode.postValue(country.phoneCode)
            _defaultCountryName.postValue(country.name)
            _defaultNameCode.postValue(country.nameCode)
        }
    }

    fun update(key: String) {
        _displayList.postValue(
            _dataSet.filter {
                it.name.contains(key, true) ||
                        it.phoneCode.contains(key, true) ||
                        it.nameCode.contains(key, true) ||
                        it.englishName.contains(key, true) ||
                        it.simpleName?.contains(key, true) == true
            }.toList()
        )
    }
}

data class Country(
    val name: String,
    val englishName: String,
    val nameCode: String,
    val phoneCode: String,
    val flag: String,
    val simpleName: String? = null,
)

fun countryCodeToEmojiFlag(countryCode: String): String {
    return countryCode.uppercase(Locale.US)
        .map { char ->
            Character.codePointAt("$char", 0) - 0x41 + 0x1F1E6
        }
        .map { codePoint ->
            Character.toChars(codePoint)
        }
        .joinToString(separator = "") {
            String(it)
        }
}

