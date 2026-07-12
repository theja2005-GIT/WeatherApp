package com.example.weatherapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.alpha
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeatherScreen() }
    }

    // A day's worth of forecast, summarized from many 3-hour ForecastItem slots
    data class ForecastDay(
        val day: String,
        val condition: String,
        val emoji: String,
        val high: Int,
        val low: Int,
        val humidity: Int,
        val windSpeed: Double,
        val rainProbability: Int
    )

    // A single 3-hour slot for the hourly forecast row
    data class HourlyItem(
        val time: String,
        val emoji: String,
        val temp: Int
    )

    // Maps an OpenWeatherMap description to a clean emoji, matching the reference design
    private fun weatherEmoji(condition: String): String {
        val c = condition.lowercase()
        return when {
            c.contains("thunder") -> "⛈️"
            c.contains("snow") -> "🌨️"
            c.contains("light rain") || c.contains("drizzle") -> "🌦️"
            c.contains("rain") -> "🌧️"
            c.contains("mist") || c.contains("fog") || c.contains("haze") -> "🌫️"
            c.contains("clear") -> "☀️"
            c.contains("few clouds") || c.contains("partly") -> "⛅"
            c.contains("cloud") -> "☁️"
            else -> "☁️"
        }
    }

    // Converts wind degrees (0-360) into a compass arrow + readable direction name
    private fun windDirectionText(deg: Int): String {
        val directions = listOf(
            "↑" to "North",
            "↗" to "North-East",
            "→" to "East",
            "↘" to "South-East",
            "↓" to "South",
            "↙" to "South-West",
            "←" to "West",
            "↖" to "North-West"
        )
        val normalized = ((deg % 360) + 360) % 360
        val index = ((normalized + 22.5) / 45).toInt() % 8
        val (arrow, label) = directions[index]
        return "$arrow $label"
    }

    // Converts a Celsius value to display text in either °C or °F, 1 decimal place
    private fun displayTemp(celsius: Double, isFahrenheit: Boolean): String {
        val value = if (isFahrenheit) celsius * 9.0 / 5.0 + 32.0 else celsius
        return String.format(Locale.US, "%.1f", value)
    }

    // Same conversion but for whole-number temps (forecast/hourly cards)
    private fun displayTempInt(celsius: Int, isFahrenheit: Boolean): Int {
        return if (isFahrenheit) (celsius * 9.0 / 5.0 + 32.0).roundToInt() else celsius
    }

    // Generates practical suggestions based on the current temperature/condition/humidity/wind
    private fun getWeatherTips(
        condition: String,
        tempC: Double,
        humidity: Int,
        windSpeedMs: Double,
        isNight: Boolean
    ): List<Pair<String, String>> {
        val c = condition.lowercase()
        val tips = mutableListOf<Pair<String, String>>()

        if (tempC >= 35) tips.add("🔥" to "It's very hot. Drink plenty of water.")
        if (tempC < 20) tips.add("🧥" to "Wear warm clothes.")
        if (c.contains("rain") || c.contains("drizzle") || c.contains("thunder")) {
            tips.add("☔" to "Carry an umbrella today.")
        }
        if (c.contains("clear") && !isNight && tempC >= 20) {
            tips.add("🌞" to "UV is high. Wear sunscreen.")
        }
        if (humidity >= 80) tips.add("💧" to "It's humid — dress light and breathable.")
        if (windSpeedMs >= 10) tips.add("💨" to "Strong winds expected.")
        if (c.contains("snow")) tips.add("❄️" to "Roads may be slippery. Drive carefully.")
        if (c.contains("thunder")) tips.add("⚡" to "Avoid open areas during the storm.")

        if (tips.isEmpty()) tips.add("👍" to "Weather looks pleasant today.")
        return tips.take(3)
    }

    // ---------- Shared color / style tokens (matches the target screenshot) ----------
    private val duskGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF232752), // deep indigo top
            Color(0xFF544C8C), // dusky purple
            Color(0xFFB07A8C), // rose
            Color(0xFFE9A96B)  // sunset orange bottom
        )
    )
    private val pillGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF6C63C4), Color(0xFF5A4FBF))
    )
    private val cardColor = Color(0xFF232145).copy(alpha = 0.55f)
    private val fieldColor = Color.White.copy(alpha = 0.12f)
    private val borderColor = Color.White.copy(alpha = 0.35f)

    // Semi-transparent scrim placed over the photo so white text stays readable
    private val scrimOverlay = Brush.verticalGradient(
        colors = listOf(
            Color(0xAA1A1B3A), // darker near the top (behind title/buttons)
            Color(0x661A1B3A),
            Color(0x99231A33),
            Color(0xCC1A1230)  // darker again near the bottom card
        )
    )

    // Chooses a color tint/scrim for the sky photo based on weather condition + day/night,
    // so the same photo reads as "sunny", "rainy", "night", "stormy" or "snowy"
    private fun scrimForCondition(condition: String, isNight: Boolean): Brush {
        val c = condition.lowercase()
        return when {
            isNight -> Brush.verticalGradient(
                colors = listOf(
                    Color(0xE60A0C22), // near-black navy, heavy
                    Color(0xCC10142E),
                    Color(0xE60D0F26)
                )
            )
            c.contains("thunder") -> Brush.verticalGradient(
                colors = listOf(
                    Color(0xE6202124), // near-black grey, stormy
                    Color(0xCC33343A),
                    Color(0xE6202124)
                )
            )
            c.contains("snow") -> Brush.verticalGradient(
                colors = listOf(
                    Color(0x99436079), // cool pale blue, lighter but still readable
                    Color(0x99516E86),
                    Color(0xB33A5468)
                )
            )
            c.contains("rain") || c.contains("drizzle") -> Brush.verticalGradient(
                colors = listOf(
                    Color(0xD922303D), // dark blue-grey, rainy mood
                    Color(0xCC2C4152),
                    Color(0xD91C2833)
                )
            )
            c.contains("clear") -> Brush.verticalGradient(
                colors = listOf(
                    Color(0x991B3A66), // brighter, warm-blue daytime tint
                    Color(0x662E5A8C),
                    Color(0x99C77A3A)
                )
            )
            else -> scrimOverlay // cloudy/overcast/default — original dusk scrim
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    // A bundled list of common city names for instant search suggestions (no extra API call needed)
    private val citySuggestionsList = listOf(
        "Coimbatore", "Coonoor", "Cochin", "Kochi", "Kozhikode", "Kollam", "Kanyakumari",
        "Chennai", "Chandigarh", "Kolkata", "Kanpur", "Kota", "Karur",
        "Madurai", "Mumbai", "Mysore", "Bangalore", "Bengaluru", "Bhopal",
        "Delhi", "Dehradun", "Hyderabad", "Hubli", "Ooty", "Pune", "Patna",
        "Salem", "Trichy", "Tiruchirappalli", "Tirupati", "Trivandrum", "Thiruvananthapuram",
        "Thanjavur", "Tirunelveli", "Vellore", "Visakhapatnam", "Vijayawada", "Coorg",
        "Nagpur", "Nashik", "Noida", "Gurgaon", "Goa", "Jaipur", "Jodhpur",
        "Lucknow", "Ludhiana", "Indore", "Agra", "Amritsar", "Ahmedabad", "Surat"
    )

    @Composable
    fun WeatherScreen() {
        var city by remember { mutableStateOf("") }
        var showSuggestions by remember { mutableStateOf(false) }
        var cityName by remember { mutableStateOf("") }
        var temperature by remember { mutableStateOf("--") }
        val context = LocalContext.current
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val activity = context as Activity
        var condition by remember { mutableStateOf("--") }
        var humidity by remember { mutableStateOf("") }
        var windSpeed by remember { mutableStateOf("") }
        var airQuality by remember { mutableStateOf("--") }
        var feelsLike by remember { mutableStateOf("") }
        var country by remember { mutableStateOf("") }
        var weatherIcon by remember { mutableStateOf("") }
        var sunrise by remember { mutableStateOf("") }
        var sunset by remember { mutableStateOf("") }
        var sunriseEpoch by remember { mutableStateOf(0L) }
        var sunsetEpoch by remember { mutableStateOf(0L) }
        var timezone by remember { mutableStateOf(0) }
        var errorMessage by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var favoriteCities by remember { mutableStateOf(listOf<String>()) }
        var forecastList by remember { mutableStateOf(listOf<ForecastDay>()) }
        var hourlyForecast by remember { mutableStateOf(listOf<HourlyItem>()) }
        var aqiValue by remember { mutableStateOf("--") }
        var isRefreshing by remember { mutableStateOf(false) }
        var isUsingCurrentLocation by remember { mutableStateOf(false) }
        var lastLat by remember { mutableStateOf(0.0) }
        var lastLon by remember { mutableStateOf(0.0) }
        var lastUpdated by remember { mutableStateOf("") }
        var windDirection by remember { mutableStateOf("") }
        var selectedForecastDay by remember { mutableStateOf<ForecastDay?>(null) }
        var recentSearches by remember { mutableStateOf(listOf<String>()) }
        var isFahrenheit by remember { mutableStateOf(false) }

        val apiKey = "dd2c53cc7af2881b95737051035b5441"

        // Keeps the 5 most recent unique searched city names, newest first
        fun addToRecentSearches(name: String) {
            if (name.isBlank() || name == "--") return
            val updated = listOf(name) + recentSearches.filter { it != name }
            recentSearches = updated.take(5)
        }

        // Fetches weather for whatever city/coords is currently in `city`.
        // Used by both the "Get Weather" button and pull-to-refresh.
        fun refreshWeather() {
            if (city.isBlank()) {
                isRefreshing = false
                return
            }
            showSuggestions = false
            isUsingCurrentLocation = false
            isLoading = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = RetrofitInstance.api.getWeather(city, apiKey)
                    val forecastResponse = RetrofitInstance.api.getForecast(city, apiKey)
                    val airResponse = RetrofitInstance.api.getAirQuality(
                        response.coord.lat, response.coord.lon, apiKey
                    )
                    withContext(Dispatchers.Main) {
                        temperature = response.main.temp.toString()
                        condition = response.weather[0].description
                        weatherIcon = response.weather[0].icon
                        feelsLike = response.main.feels_like.toString()
                        humidity = response.main.humidity.toString()
                        windSpeed = response.wind.speed.toString()
                        windDirection = windDirectionText(response.wind.deg)
                        country = response.sys.country
                        cityName = response.name
                        addToRecentSearches(response.name)
                        timezone = response.timezone
                        sunrise = formatTime(response.sys.sunrise.toString(), timezone)
                        sunset = formatTime(response.sys.sunset.toString(), timezone)
                        sunriseEpoch = response.sys.sunrise
                        sunsetEpoch = response.sys.sunset
                        forecastList = getDailyForecast(forecastResponse.list, response.timezone)
                        hourlyForecast = getHourlyForecast(forecastResponse.list, response.timezone)
                        val aqi = airResponse.list.firstOrNull()?.main?.aqi ?: 0
                        airQuality = when (aqi) {
                            1 -> "Good"; 2 -> "Fair"; 3 -> "Moderate"
                            4 -> "Poor"; 5 -> "Very Poor"; else -> "Unknown"
                        }
                        aqiValue = aqi.toString()
                        errorMessage = ""
                        lastUpdated = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                        isLoading = false
                        isRefreshing = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        temperature = "Not Found"
                        condition = "Unknown"
                        feelsLike = "--"; humidity = "--"; windSpeed = "--"
                        country = "--"; cityName = "--"; sunrise = "--"; sunset = "--"
                        weatherIcon = ""
                        errorMessage = "❌ City not found. Please enter a valid city."
                        isLoading = false
                        isRefreshing = false
                    }
                }
            }
        }

        // Fetches weather directly by coordinates using the dedicated lat/lon endpoints
        // (getWeather/getForecast only support city-name search, not coordinates)
        fun fetchWeatherForCoords(lat: Double, lon: Double) {
            isLoading = true
            isUsingCurrentLocation = true
            lastLat = lat
            lastLon = lon
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = RetrofitInstance.api.getWeatherByCoords(lat, lon, apiKey)
                    val forecastResponse = RetrofitInstance.api.getForecastByCoords(lat, lon, apiKey)
                    val airResponse = RetrofitInstance.api.getAirQuality(lat, lon, apiKey)
                    withContext(Dispatchers.Main) {
                        temperature = response.main.temp.toString()
                        condition = response.weather[0].description
                        weatherIcon = response.weather[0].icon
                        feelsLike = response.main.feels_like.toString()
                        humidity = response.main.humidity.toString()
                        windSpeed = response.wind.speed.toString()
                        windDirection = windDirectionText(response.wind.deg)
                        country = response.sys.country
                        cityName = response.name
                        addToRecentSearches(response.name)
                        timezone = response.timezone
                        sunrise = formatTime(response.sys.sunrise.toString(), timezone)
                        sunset = formatTime(response.sys.sunset.toString(), timezone)
                        sunriseEpoch = response.sys.sunrise
                        sunsetEpoch = response.sys.sunset
                        forecastList = getDailyForecast(forecastResponse.list, response.timezone)
                        hourlyForecast = getHourlyForecast(forecastResponse.list, response.timezone)
                        val aqi = airResponse.list.firstOrNull()?.main?.aqi ?: 0
                        airQuality = when (aqi) {
                            1 -> "Good"; 2 -> "Fair"; 3 -> "Moderate"
                            4 -> "Poor"; 5 -> "Very Poor"; else -> "Unknown"
                        }
                        aqiValue = aqi.toString()
                        errorMessage = ""
                        lastUpdated = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                        isLoading = false
                        isRefreshing = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "❌ Unable to get weather for your location."
                        isLoading = false
                        isRefreshing = false
                    }
                }
            }
        }

        // Gets a fresh GPS/network location (not a stale cached one) and fetches weather for it.
        // Must only be called after location permission is already granted.
        fun fetchCurrentLocationWeather() {
            isLoading = true
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    fetchWeatherForCoords(location.latitude, location.longitude)
                } else {
                    isLoading = false
                    errorMessage = "❌ Couldn't get your location. Make sure Location/GPS is turned on, then try again."
                }
            }.addOnFailureListener {
                isLoading = false
                errorMessage = "❌ Failed to get location: ${it.message}"
            }
        }

        val nowEpoch = System.currentTimeMillis() / 1000
        val isNight = sunriseEpoch > 0 && (nowEpoch < sunriseEpoch || nowEpoch > sunsetEpoch)

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                if (isUsingCurrentLocation) {
                    fetchWeatherForCoords(lastLat, lastLon)
                } else {
                    refreshWeather()
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Real sky photo — put your downloaded image at res/drawable/bg_sunset.jpg
                Image(
                    painter = painterResource(id = R.drawable.bg_sunset),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Dynamic tint so the same photo reads as sunny / rainy / night / stormy / snowy
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrimForCondition(condition, isNight))
                )

                if (isNight) {
                    // Twinkling stars + a soft moon glow for night skies
                    StarsField()
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .offset(x = 220.dp, y = 60.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xCCE8ECF5), Color(0x00E8ECF5))
                                ),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                } else if (condition.lowercase().contains("clear")) {
                    // Soft glowing sun, only for clear daytime skies
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .offset(x = 140.dp, y = 480.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0x66FFD54F),
                                        Color(0x00FFD54F)
                                    )
                                ),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "Weather App",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ---- °C / °F unit switch ----
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "°C",
                            color = if (!isFahrenheit) Color.White else Color.White.copy(alpha = 0.5f),
                            fontWeight = if (!isFahrenheit) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp
                        )
                        Switch(
                            checked = isFahrenheit,
                            onCheckedChange = { isFahrenheit = it },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF6C63C4),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF6C63C4)
                            )
                        )
                        Text(
                            "°F",
                            color = if (isFahrenheit) Color.White else Color.White.copy(alpha = 0.5f),
                            fontWeight = if (isFahrenheit) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ---- Search field ----
                    OutlinedTextField(
                        value = city,
                        onValueChange = {
                            city = it
                            showSuggestions = it.isNotBlank()
                        },
                        label = { Text("Enter City", color = Color.White.copy(alpha = 0.8f)) },
                        leadingIcon = { Text("📍") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = fieldColor,
                            unfocusedContainerColor = fieldColor,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = borderColor,
                            unfocusedBorderColor = borderColor,
                            cursorColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ---- Search suggestions dropdown ----
                    val suggestions = remember(city, showSuggestions) {
                        if (showSuggestions && city.length >= 2) {
                            citySuggestionsList.filter {
                                it.startsWith(city, ignoreCase = true) && !it.equals(city, ignoreCase = true)
                            }.take(5)
                        } else emptyList()
                    }
                    if (suggestions.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            border = BorderStroke(1.dp, borderColor)
                        ) {
                            Column {
                                suggestions.forEach { suggestion ->
                                    Text(
                                        text = "📍 $suggestion",
                                        color = Color.White,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                city = suggestion
                                                showSuggestions = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ---- Action buttons (pill style) ----
                    PillButton(icon = "🌦️", label = "Get Weather") {
                        refreshWeather()
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    PillButton(icon = "⭐", label = "Add to Favorites") {
                        if (city.isNotBlank() && !favoriteCities.contains(city)) {
                            favoriteCities = favoriteCities + city
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Requests location permission if needed; Android shows the system dialog here
                    val locationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            fetchCurrentLocationWeather()
                        } else {
                            errorMessage = "❌ Location permission denied. Enable it in Settings to use this feature."
                        }
                    }

                    PillButton(icon = "📍", label = "Use Current Location") {
                        if (ActivityCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            fetchCurrentLocationWeather()
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }

                    if (favoriteCities.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("⭐ Favorite Cities", color = Color.White, fontWeight = FontWeight.SemiBold)
                        favoriteCities.forEach { favorite ->
                            Text(
                                text = "📍 $favorite",
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .clickable {
                                        city = favorite
                                        isUsingCurrentLocation = false
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                val response = RetrofitInstance.api.getWeather(favorite, apiKey)
                                                val forecastResponse = RetrofitInstance.api.getForecast(favorite, apiKey)
                                                withContext(Dispatchers.Main) {
                                                    cityName = favorite
                                                    temperature = response.main.temp.toString()
                                                    condition = response.weather[0].description
                                                    weatherIcon = response.weather[0].icon
                                                    feelsLike = response.main.feels_like.toString()
                                                    humidity = response.main.humidity.toString()
                                                    windSpeed = response.wind.speed.toString()
                                                    windDirection = windDirectionText(response.wind.deg)
                                                    country = response.sys.country
                                                    timezone = response.timezone
                                                    sunrise = formatTime(response.sys.sunrise.toString(), timezone)
                                                    sunset = formatTime(response.sys.sunset.toString(), timezone)
                                                    sunriseEpoch = response.sys.sunrise
                                                    sunsetEpoch = response.sys.sunset
                                                    forecastList = getDailyForecast(forecastResponse.list, response.timezone)
                                                    hourlyForecast = getHourlyForecast(forecastResponse.list, response.timezone)
                                                    lastUpdated = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                                                    addToRecentSearches(favorite)
                                                }
                                            } catch (e: Exception) { }
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }

                    // ---- Recent Searches ----
                    if (recentSearches.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("🕒 Recent Searches", color = Color.White, fontWeight = FontWeight.SemiBold)
                        recentSearches.forEach { recent ->
                            Text(
                                text = "📍 $recent",
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .clickable {
                                        city = recent
                                        isUsingCurrentLocation = false
                                        refreshWeather()
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }

                    if (errorMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(errorMessage, color = Color(0xFFFFCDD2))
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    if (isLoading) {
                        WeatherLoadingAnimation()
                    } else if (condition != "--" && condition != "Unknown") {
                        WeatherAnimation(condition = condition, modifier = Modifier.height(120.dp))
                    }

                    // ---- Main glass info card ----
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        border = BorderStroke(1.dp, borderColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📍 $cityName", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)

                            Spacer(modifier = Modifier.height(8.dp))

                            val unitSymbol = if (isFahrenheit) "°F" else "°C"
                            val tempCelsius = temperature.toDoubleOrNull()
                            val feelsCelsius = feelsLike.toDoubleOrNull()

                            Text(
                                text = if (tempCelsius != null) "${displayTemp(tempCelsius, isFahrenheit)}$unitSymbol" else "--",
                                color = Color.White,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (feelsCelsius != null) "Feels like ${displayTemp(feelsCelsius, isFahrenheit)}$unitSymbol" else "Feels like --",
                                color = Color.White.copy(alpha = 0.8f)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = condition.replaceFirstChar { it.uppercase() },
                                color = Color(0xFFB9A6FF),
                                fontWeight = FontWeight.SemiBold
                            )

                            Divider(
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.padding(vertical = 16.dp)
                            )

                            InfoRow(
                                "💧", "Humidity", "$humidity %",
                                "🌬️", "Wind", if (windDirection.isNotEmpty()) "$windDirection\n$windSpeed m/s" else "$windSpeed m/s"
                            )
                            InfoRow("🍃", "Air Quality", airQuality, "📊", "AQI", aqiValue)
                            InfoRow("🌍", "Country", country, "🌅", "Sunrise", sunrise)

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("🌇 Sunset: $sunset", color = Color.White.copy(alpha = 0.9f))

                            if (lastUpdated.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "Updated: $lastUpdated",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // ---- Weather tips ----
                    if (condition != "--" && condition != "Unknown") {
                        val tempValue = temperature.toDoubleOrNull() ?: 0.0
                        val humidityValue = humidity.toIntOrNull() ?: 0
                        val windValue = windSpeed.toDoubleOrNull() ?: 0.0
                        val tips = getWeatherTips(condition, tempValue, humidityValue, windValue, isNight)

                        Spacer(modifier = Modifier.height(20.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            border = BorderStroke(1.dp, borderColor)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "⚠️ Weather Alert",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                tips.forEach { (emoji, message) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("$emoji ", fontSize = 16.sp)
                                        Text(message, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ---- Hourly forecast (next 24h) ----
                    if (hourlyForecast.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🕐 ", fontSize = 20.sp)
                            Text(
                                "Hourly Forecast",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(hourlyForecast) { hour ->
                                HourlyCard(time = hour.time, emoji = hour.emoji, temp = displayTempInt(hour.temp, isFahrenheit).toString())
                            }
                        }
                    }

                    // ---- 5-day forecast ----
                    if (forecastList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📅 ", fontSize = 20.sp)
                            Text(
                                "5-Day Forecast",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(forecastList) { day ->
                                ForecastCard(
                                    day = day.day,
                                    emoji = day.emoji,
                                    condition = day.condition,
                                    high = displayTempInt(day.high, isFahrenheit).toString(),
                                    low = displayTempInt(day.low, isFahrenheit).toString(),
                                    onClick = { selectedForecastDay = day }
                                )
                            }
                        }
                    }
                }
            }
        } // end PullToRefreshBox

        selectedForecastDay?.let { day ->
            ForecastDetailsDialog(
                day = day,
                sunrise = sunrise,
                sunset = sunset,
                isFahrenheit = isFahrenheit,
                onDismiss = { selectedForecastDay = null }
            )
        }
    }

    // ---------- Dynamic background decorations ----------

    @Composable
    private fun StarsField() {
        val infinite = rememberInfiniteTransition(label = "stars")
        val twinkle by infinite.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
            label = "twinkle"
        )
        // Fixed pseudo-random star positions so they don't jump around on recomposition
        val starPositions = remember {
            val rnd = kotlin.random.Random(42)
            List(28) { Offset(rnd.nextFloat(), rnd.nextFloat() * 0.6f) }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            starPositions.forEachIndexed { index, pos ->
                val x = pos.x * size.width
                val y = pos.y * size.height
                val alpha = if (index % 3 == 0) twinkle else 0.7f
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = if (index % 5 == 0) 3f else 1.6f,
                    center = Offset(x, y)
                )
            }
        }
    }

    // ---------- Loading animation ----------

    @Composable
    private fun WeatherLoadingAnimation() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                SunAnimation()
                CloudsAnimation()
            }
            Spacer(modifier = Modifier.height(4.dp))
            LoadingDots()
            Spacer(modifier = Modifier.height(6.dp))
            Text("Fetching weather...", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }

    @Composable
    private fun LoadingDots() {
        val infinite = rememberInfiniteTransition(label = "dots")
        Row {
            repeat(3) { i ->
                val bounce by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = -8f,
                    animationSpec = infiniteRepeatable(
                        tween(400, delayMillis = i * 150, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "dot$i"
                )
                Text(
                    "•",
                    fontSize = 26.sp,
                    color = Color.White,
                    modifier = Modifier
                        .offset(y = bounce.dp)
                        .padding(horizontal = 2.dp)
                )
            }
        }
    }

    // ---------- Weather animations ----------

    @Composable
    private fun WeatherAnimation(condition: String, modifier: Modifier = Modifier) {
        val c = condition.lowercase()
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            when {
                c.contains("thunder") -> ThunderstormAnimation()
                c.contains("snow") -> SnowAnimation()
                c.contains("rain") || c.contains("drizzle") -> RainAnimation()
                c.contains("clear") -> SunAnimation()
                else -> CloudsAnimation() // covers cloudy, overcast, mist, fog, haze, etc.
            }
        }
    }

    @Composable
    private fun SunAnimation() {
        val infinite = rememberInfiniteTransition(label = "sun")
        val rotation by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
            label = "sunRotation"
        )
        val pulse by infinite.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sunPulse"
        )
        Canvas(modifier = Modifier.size(100.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.minDimension / 4f) * pulse
            rotate(rotation, pivot = center) {
                for (i in 0 until 12) {
                    val angle = (i * 30f) * (PI / 180f)
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    drawLine(
                        color = Color(0xFFFFD54F),
                        start = Offset(center.x + cosA * (radius + 8), center.y + sinA * (radius + 8)),
                        end = Offset(center.x + cosA * (radius + 24), center.y + sinA * (radius + 24)),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                }
            }
            drawCircle(color = Color(0xFFFFCA28), radius = radius, center = center)
        }
    }

    @Composable
    private fun CloudsAnimation() {
        val infinite = rememberInfiniteTransition(label = "clouds")
        val driftBack by infinite.animateFloat(
            initialValue = -18f, targetValue = 18f,
            animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse),
            label = "cloudBack"
        )
        val driftFront by infinite.animateFloat(
            initialValue = 14f, targetValue = -14f,
            animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
            label = "cloudFront"
        )
        Box(modifier = Modifier.size(width = 140.dp, height = 90.dp)) {
            Text("☁️", fontSize = 38.sp, modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = driftBack.dp, y = (-8).dp))
            Text("☁️", fontSize = 50.sp, modifier = Modifier
                .align(Alignment.Center)
                .offset(x = driftFront.dp, y = 10.dp))
        }
    }

    @Composable
    private fun RainAnimation() {
        val infinite = rememberInfiniteTransition(label = "rain")
        Box(modifier = Modifier.size(width = 140.dp, height = 110.dp)) {
            Text("☁️", fontSize = 48.sp, modifier = Modifier.align(Alignment.TopCenter))
            repeat(7) { i ->
                val fall by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = 55f,
                    animationSpec = infiniteRepeatable(
                        tween(650 + (i % 3) * 150, delayMillis = i * 80, easing = LinearEasing)
                    ),
                    label = "drop$i"
                )
                Text(
                    "💧",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (12 + i * 17).dp, y = (44 + fall).dp)
                        .alpha((1f - fall / 55f).coerceIn(0.15f, 1f))
                )
            }
        }
    }

    @Composable
    private fun ThunderstormAnimation() {
        val infinite = rememberInfiniteTransition(label = "storm")
        val flashAlpha by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 3000
                    0f at 0
                    0f at 1200
                    0.75f at 1300
                    0f at 1400
                    0f at 1450
                    0.5f at 1550
                    0f at 1650
                    0f at 3000
                }
            ),
            label = "flash"
        )
        Box(modifier = Modifier.size(width = 140.dp, height = 110.dp)) {
            RainAnimation()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )
            Text("⚡", fontSize = 24.sp, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable
    private fun SnowAnimation() {
        val infinite = rememberInfiniteTransition(label = "snow")
        Box(modifier = Modifier.size(width = 140.dp, height = 110.dp)) {
            Text("☁️", fontSize = 44.sp, modifier = Modifier.align(Alignment.TopCenter))
            repeat(6) { i ->
                val fall by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = 65f,
                    animationSpec = infiniteRepeatable(
                        tween(1800 + (i % 4) * 200, delayMillis = i * 150, easing = LinearEasing)
                    ),
                    label = "flake$i"
                )
                val sway by infinite.animateFloat(
                    initialValue = -6f,
                    targetValue = 6f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                    label = "sway$i"
                )
                Text(
                    "❄️",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (10 + i * 20 + sway).dp, y = (44 + fall).dp)
                )
            }
        }
    }

    // ---------- Reusable pieces ----------

    @Composable
    private fun PillButton(icon: String, label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(pillGradient)
                .border(1.dp, borderColor, RoundedCornerShape(50))
                .clickable { onClick() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$icon  $label",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }

    @Composable
    private fun InfoRow(
        icon1: String, label1: String, value1: String,
        icon2: String, label2: String, value2: String
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InfoItem(icon1, label1, value1)
            InfoItem(icon2, label2, value2)
        }
    }

    @Composable
    private fun InfoItem(icon: String, label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$icon ", fontSize = 18.sp)
            Column {
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun HourlyCard(time: String, emoji: String, temp: String) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.width(72.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("$temp°", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun ForecastCard(
        day: String,
        emoji: String,
        condition: String,
        high: String,
        low: String,
        onClick: () -> Unit
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .width(96.dp)
                .clickable { onClick() }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(day, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(emoji, fontSize = 28.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    condition,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("$high° / $low°", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun ForecastDetailsDialog(
        day: ForecastDay,
        sunrise: String,
        sunset: String,
        isFahrenheit: Boolean,
        onDismiss: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF232145),
            title = {
                Text("${day.emoji} ${day.day} — ${day.condition}", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    val high = displayTempInt(day.high, isFahrenheit)
                    val low = displayTempInt(day.low, isFahrenheit)
                    Text("High / Low: $high° / $low°", color = Color.White.copy(alpha = 0.9f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("💧 Humidity: ${day.humidity}%", color = Color.White.copy(alpha = 0.9f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🌬️ Wind: ${day.windSpeed} m/s", color = Color.White.copy(alpha = 0.9f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🌧️ Rain probability: ${day.rainProbability}%", color = Color.White.copy(alpha = 0.9f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🌅 Sunrise: $sunrise", color = Color.White.copy(alpha = 0.9f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🌇 Sunset: $sunset", color = Color.White.copy(alpha = 0.9f))
                }
            },
            confirmButton = {
                Text(
                    "Close",
                    color = Color(0xFFB9A6FF),
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(8.dp)
                )
            }
        )
    }

    // ---------- Unchanged logic ----------

    // Takes the next 8 raw 3-hour slots (~24 hours) for the hourly forecast row
    fun getHourlyForecast(list: List<ForecastItem>, timezone: Int): List<HourlyItem> {
        val timeFormat = SimpleDateFormat("h a", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return list.take(8).map { item ->
            val date = Date((item.dt + timezone) * 1000)
            val description = item.weather.firstOrNull()?.description ?: ""
            HourlyItem(
                time = timeFormat.format(date),
                emoji = weatherEmoji(description),
                temp = item.main.temp.toInt()
            )
        }
    }

    fun getDailyForecast(list: List<ForecastItem>, timezone: Int): List<ForecastDay> {
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val hourFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val labelFormat = SimpleDateFormat("EEE", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        // Group every 3-hour slot by calendar day (in the location's local timezone)
        val groupedByDay = linkedMapOf<String, MutableList<ForecastItem>>()
        for (item in list) {
            val date = Date((item.dt + timezone) * 1000)
            val dayKey = dayKeyFormat.format(date)
            groupedByDay.getOrPut(dayKey) { mutableListOf() }.add(item)
        }

        return groupedByDay.entries.take(5).map { (dayKey, items) ->
            val high = items.maxOf { it.main.temp }.toInt()
            val low = items.minOf { it.main.temp }.toInt()

            // Use the slot closest to noon to represent that day's overall condition/icon
            val midDayItem = items.minByOrNull { item ->
                val date = Date((item.dt + timezone) * 1000)
                val hour = hourFormat.format(date).substring(0, 2).toInt()
                kotlin.math.abs(hour - 12)
            } ?: items.first()

            val condition = midDayItem.weather.firstOrNull()?.description
                ?.replaceFirstChar { it.uppercase() } ?: "--"
            val dayLabel = labelFormat.format(Date((midDayItem.dt + timezone) * 1000))

            ForecastDay(
                day = dayLabel,
                condition = condition,
                emoji = weatherEmoji(condition),
                high = high,
                low = low,
                humidity = midDayItem.main.humidity,
                windSpeed = midDayItem.wind.speed,
                rainProbability = (midDayItem.pop * 100).toInt()
            )
        }
    }

    fun formatTime(timestamp: String, timezone: Int): String {
        val date = Date((timestamp.toLong() + timezone) * 1000)
        val format = SimpleDateFormat("hh:mm a", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(date)
    }
}
