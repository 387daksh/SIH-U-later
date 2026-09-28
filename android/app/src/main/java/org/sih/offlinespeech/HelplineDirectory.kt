package org.sih.offlinespeech

/** Offline nationwide contacts transcribed from the National Portal of India helpline page. */
data class HelplineContact(
    val name: String,
    val number: String,
    val category: String,
    val scope: String = "India-wide",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

object HelplineDirectory {
    const val sourceUrl = "https://www.india.gov.in/directory/helpline"
    const val districtControlSourceUrl = "https://ndmindia.mha.gov.in/ndmi/images/pdf/Free-Public-Utility-Code-100315.pdf"
    const val lastChecked = "2026-09-26"
    const val emergencyNumber = "112"

    val contacts = listOf(
        HelplineContact("Emergency response (ERSS)", "112", "Emergency"),
        HelplineContact("State relief commissioner control room", "1070", "Emergency"),
        HelplineContact("District collector / magistrate control room", "1077", "Emergency"),
        HelplineContact("National Ambulance Service", "102", "Emergency"),
        HelplineContact("Police helpline", "100", "Emergency"),
        HelplineContact("Fire helpline", "101", "Emergency"),
        HelplineContact("Cyber Crime Helpline", "1930", "Emergency"),
        HelplineContact("LPG leak helpline", "1906", "Emergency"),
        HelplineContact("Relief Commissioner · natural calamities", "1070", "Emergency"),
        HelplineContact("Earthquake / flood / NDRF", "011-24363260", "Emergency"),
        HelplineContact("National Consumer Helpline", "1915", "Emergency"),
        HelplineContact("Road accident helpline", "1073", "Emergency"),
        HelplineContact("National Narcotics Helpline (MANAS)", "1933", "Emergency"),
        HelplineContact("National Commission for Women", "7827170170", "Women & safety"),
        HelplineContact("Shakti Shalini", "10920", "Women & safety"),
        HelplineContact("Sakhi Women's Helpline", "011-24619821", "Women & safety"),
        HelplineContact("Women helpline", "181", "Women & safety"),
        HelplineContact("Rape crisis helpline", "1800-2000-737", "Women & safety"),
        HelplineContact("SUBHADRA Yojana", "14678", "Women & safety"),
        HelplineContact("Child helpline", "1098", "Children & rights"),
        HelplineContact("National Human Rights Commission", "14433", "Children & rights"),
        HelplineContact("Anti-obscene calls cell", "1091", "Children & rights"),
        HelplineContact("Senior citizens helpline", "011-20893999", "Senior & accessibility"),
        HelplineContact("Senior citizens helpline", "14567", "Senior & accessibility"),
        HelplineContact("Persons with disabilities helpline", "14456", "Senior & accessibility"),
        HelplineContact("Railway security / medical assistance", "139", "Enquiries"),
        HelplineContact("State Bank of India", "3800", "Enquiries"),
        HelplineContact("National Scholarship Portal helpdesk", "0120-6619540", "Enquiries"),
        HelplineContact("Passport Seva · national", "1800-258-1800", "Enquiries"),
        HelplineContact("Passport Seva · J&K (paid)", "040-66720567", "Enquiries", "Jammu & Kashmir"),
        HelplineContact("Passport Seva · North-East (paid)", "040-66720581", "Enquiries", "North-East states"),
        HelplineContact("COVID-19 central helpline", "011-23978046", "Other services"),
        HelplineContact("COVID-19 toll-free", "1075", "Other services"),
        HelplineContact("Tourist helpline", "1800-11-1363", "Other services"),
        HelplineContact("Tourist helpline · short code", "1363", "Other services"),
        HelplineContact("UIDAI toll-free", "1947", "Other services"),
        HelplineContact("PM Daksh helpline", "1800110396", "Other services"),
        HelplineContact("FSSAI helpdesk", "1800112100", "Other services"),
    )

    /** Only coordinate-backed facilities can be described as nearest; nationwide hotlines cannot. */
    fun nearestFacilities(latitude: Double, longitude: Double): List<Pair<HelplineContact, Double>> {
        if (!TeamFix.valid(latitude, longitude, 1.0)) return emptyList()
        return contacts.mapNotNull { contact ->
            val lat = contact.latitude ?: return@mapNotNull null
            val lon = contact.longitude ?: return@mapNotNull null
            contact to MapDistanceCalculator.haversineDistanceMeters(latitude, longitude, lat, lon)
        }.sortedBy { it.second }
    }
}
