package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.Locale

object DeezerCountries {

    private const val COUNTRY_CODE_KEY = "countryCode"
    private const val LANGUAGE_CODE_KEY = "languageCode"

    data class Country(val name: String, val code: String)
    data class Language(val name: String, val code: String)

    private const val COUNTRY_DATA = "Afghanistan\tAF\nAlbania\tAL\nAlgeria\tDZ\nAngola\tAO\nAnguilla\tAI\nAntigua and Barbuda\tAG\nArgentina\tAR\nArmenia\tAM\nAustralia\tAU\nAustria\tAT\nAzerbaijan\tAZ\nBahrain\tBH\nBangladesh\tBD\nBarbados\tBB\nBelgium\tBE\nBenin\tBJ\nBhutan\tBT\nBolivia\tBO\nBosnia and Herzegovina\tBA\nBotswana\tBW\nBrazil\tBR\nBritish Indian Ocean Territory\tIO\nBritish Virgin Islands\tVG\nBrunei\tBN\nBulgaria\tBG\nBurkina Faso\tBF\nBurundi\tBI\nCambodia\tKH\nCameroon\tCM\nCanada\tCA\nCape Verde\tCV\nCayman Islands\tKY\nCentral African Republic\tCF\nChad\tTD\nChile\tCL\nChristmas Island\tCX\nCocos Islands\tCC\nColombia\tCO\nCook Islands\tCK\nCosta Rica\tCR\nCroatia\tHR\nCyprus\tCY\nCzech Republic\tCZ\nDemocratic Republic of the Congo\tCD\nDenmark\tDK\nDjibouti\tDJ\nDominica\tDM\nDominican Republic\tDO\nEast Timor\tTL\nEcuador\tEC\nEgypt\tEG\nEl Salvador\tSV\nEquatorial Guinea\tGQ\nEritrea\tER\nEstonia\tEE\nEthiopia\tET\nFederated States of Micronesia\tFM\nFiji\tFJ\nFinland\tFI\nFrance\tFR\nGabon\tGA\nGambia\tGM\nGeorgia\tGE\nGermany\tDE\nGhana\tGH\nGreece\tGR\nGrenada\tGD\nGuatemala\tGT\nGuinea\tGN\nGuinea-Bissau\tGW\nHonduras\tHN\nHungary\tHU\nIceland\tIS\nIndonesia\tID\nIraq\tIQ\nIreland\tIE\nIsrael\tIL\nItaly\tIT\nJamaica\tJM\nJapan\tJP\nJordan\tJO\nKazakhstan\tKZ\nKenya\tKE\nKiribati\tKI\nKuwait\tKW\nKyrgyzstan\tKG\nLaos\tLA\nLatvia\tLV\nLebanon\tLB\nLesotho\tLS\nLiberia\tLR\nLibya\tLY\nLithuania\tLT\nLuxembourg\tLU\nNorth Macedonia\tMK\nMadagascar\tMG\nMalawi\tMW\nMalaysia\tMY\nMali\tML\nMalta\tMT\nMarshall Islands\tMH\nMauritania\tMR\nMauritius\tMU\nMexico\tMX\nMoldova\tMD\nMongolia\tMN\nMontenegro\tME\nMontserrat\tMS\nMorocco\tMA\nMozambique\tMZ\nNamibia\tNA\nNauru\tNR\nNepal\tNP\nNew Zealand\tNZ\nNicaragua\tNI\nNiger\tNE\nNigeria\tNG\nNiue\tNU\nNorfolk Island\tNF\nNorway\tNO\nOman\tOM\nPakistan\tPK\nPalau\tPW\nPanama\tPA\nPapua New Guinea\tPG\nParaguay\tPY\nPeru\tPE\nPoland\tPL\nPortugal\tPT\nQatar\tQA\nRepublic of the Congo\tCG\nRomania\tRO\nRwanda\tRW\nSaint Kitts and Nevis\tKN\nSaint Lucia\tLC\nSaint Vincent and the Grenadines\tVC\nSamoa\tWS\nSão Tomé and Príncipe\tST\nSaudi Arabia\tSA\nSenegal\tSN\nSerbia\tRS\nSeychelles\tSC\nSierra Leone\tSL\nSingapore\tSG\nSlovakia\tSK\nSlovenia\tSI\nSomalia\tSO\nSouth Africa\tZA\nSpain\tES\nSri Lanka\tLK\nSvalbard and Jan Mayen\tSJ\nEswatini\tSZ\nSweden\tSE\nSwitzerland\tCH\nTajikistan\tTJ\nTanzania\tTZ\nThailand\tTH\nComoros\tKM\nFalkland Islands\tFK\nIvory Coast\tCI\nMaldives\tMV\nNetherlands\tNL\nPhilippines\tPH\nPitcairn Islands\tPN\nSolomon Islands\tSB\nTogo\tTG\nTokelau\tTK\nTonga\tTO\nTunisia\tTN\nTurkey\tTR\nTurkmenistan\tTM\nTurks and Caicos Islands\tTC\nTuvalu\tTV\nUganda\tUG\nUkraine\tUA\nUnited Arab Emirates\tAE\nUnited Kingdom\tGB\nUnited States of America\tUS\nUruguay\tUY\nUzbekistan\tUZ\nVanuatu\tVU\nVenezuela\tVE\nVietnam\tVN\nYemen\tYE\nZambia\tZM\nZimbabwe\tZW"

    private const val LANGUAGE_DATA = "English (UK)\ten-GB\nFrench\tfr-FR\nGerman\tde-DE\nSpanish (Spain)\tes-ES\nItalian\tit-IT\nDutch\tnl-NL\nPortuguese (Portugal)\tpt-PT\nRussian\tru-RU\nPortuguese (Brazil)\tpt-BR\nPolish\tpl-PL\nTurkish\ttr-TR\nRomanian\tro-RO\nHungarian\thu-HU\nSerbian\tsr-RS\nArabic\tar-SA\nCroatian\thr-HR\nSpanish (Mexico)\tes-MX\nCzech\tcs-CZ\nSlovak\tsk-SK\nSwedish\tsv-SE\nEnglish (US)\ten-US\nJapanese\tja-JP\nBulgarian\tbg-BG\nDanish\tda-DK\nFinnish\tfi-FI\nSlovenian\tsl-SI\nUkrainian\tuk-UA"

    val countries: List<Country> by lazy {
        COUNTRY_DATA.split('\n').map { line ->
            val tab = line.indexOf('\t')
            Country(line.take(tab), line.substring(tab + 1))
        }
    }

    val languages: List<Language> by lazy {
        LANGUAGE_DATA.split('\n').map { line ->
            val tab = line.indexOf('\t')
            Language(line.take(tab), line.substring(tab + 1))
        }
    }

    fun getDefaultCountryIndex(settings: Settings?): Int {
        val storedCountryCode = settings?.getString(COUNTRY_CODE_KEY)
        val countryCode = storedCountryCode ?: Locale.getDefault().country.also {
            settings?.putString(COUNTRY_CODE_KEY, it)
        }
        return countries.indexOfFirst { it.code.equals(countryCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }

    fun getDefaultLanguageIndex(settings: Settings?): Int {
        val storedLanguageCode = settings?.getString(LANGUAGE_CODE_KEY)
        val languageCode = storedLanguageCode ?: Locale.getDefault().toLanguageTag().also {
            settings?.putString(LANGUAGE_CODE_KEY, it)
        }
        return languages.indexOfFirst { it.code.equals(languageCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }
}