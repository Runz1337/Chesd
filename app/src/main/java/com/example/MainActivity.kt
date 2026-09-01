package com.example

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MainViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _threshold = MutableStateFlow(3.0f)
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _currentCount = MutableStateFlow(0)
    val currentCount: StateFlow<Int> = _currentCount.asStateFlow()

    private val _tokens = MutableStateFlow<List<Int>>(emptyList())
    val tokens: StateFlow<List<Int>> = _tokens.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()
    
    private val _currentMagnitude = MutableStateFlow(0f)
    val currentMagnitude: StateFlow<Float> = _currentMagnitude.asStateFlow()

    private var lastContractionTime = 0L
    private val debounceTimeMs = 300L
    private val gapTimeMs = 1500L
    
    private var gapJob: Job? = null

    fun toggleListening() {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (linearAccelerationSensor != null) {
            sensorManager.registerListener(this, linearAccelerationSensor, SensorManager.SENSOR_DELAY_GAME)
            _isListening.value = true
            addMessage("Started listening for contractions.")
        } else {
            addMessage("Linear acceleration sensor not available.")
        }
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        _isListening.value = false
        addMessage("Stopped listening.")
        finalizeCurrentCount()
    }

    fun setThreshold(value: Float) {
        _threshold.value = value
    }
    
    fun clearTokens() {
        _tokens.value = emptyList()
        _currentCount.value = 0
        addMessage("Cleared decoded message.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            _currentMagnitude.value = magnitude

            if (magnitude > _threshold.value) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastContractionTime > debounceTimeMs) {
                    lastContractionTime = currentTime
                    _currentCount.value += 1
                    
                    // Restart gap timer
                    gapJob?.cancel()
                    gapJob = viewModelScope.launch {
                        delay(gapTimeMs)
                        finalizeCurrentCount()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    private fun finalizeCurrentCount() {
        val count = _currentCount.value
        if (count > 0) {
            val currentList = _tokens.value.toMutableList()
            currentList.add(count)
            _tokens.value = currentList
            _currentCount.value = 0
            addMessage("Detected sequence: $count")
        }
    }

    private fun addMessage(msg: String) {
        val currentList = _messages.value.toMutableList()
        currentList.add(0, msg) // Add to top
        if (currentList.size > 50) currentList.removeLast()
        _messages.value = currentList
    }

    fun simulateResponse() {
        val responseTokens = listOf(2, 3)
        addMessage("Simulating incoming vibration reply: [2, 3]")
        viewModelScope.launch {
            vibrateTokens(responseTokens)
        }
    }
    
    fun decodeTokens(tokenList: List<Int>): String {
        if (tokenList.isEmpty()) return ""
        val sb = java.lang.StringBuilder()
        
        val firstToken = tokenList[0]
        if (firstToken in 1..26) {
            val letter = (firstToken + 64).toChar()
            sb.append(letter).append(" ")
        } else {
            sb.append("? ")
        }
        
        for (i in 1 until tokenList.size) {
            val num = tokenList[i]
            // For numbers, 10 could mean 0, but let's just map literally for simplicity
            if (num == 10) sb.append("0 ") else sb.append("$num ")
        }
        
        return sb.toString().trim()
    }

    private suspend fun vibrateTokens(tokens: List<Int>) {
        if (!vibrator.hasVibrator()) {
            addMessage("Device does not have a vibrator.")
            return
        }
        
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        
        // Start with a small delay
        timings.add(500)
        amplitudes.add(0)
        
        for (i in tokens.indices) {
            val token = tokens[i]
            for (j in 0 until token) {
                // Vibrate for 150ms
                timings.add(150)
                amplitudes.add(255) // Max amplitude
                
                // Pause between contractions of the same token
                if (j < token - 1) {
                    timings.add(250)
                    amplitudes.add(0)
                }
            }
            
            // Gap between different tokens
            if (i < tokens.size - 1) {
                timings.add(1000)
                amplitudes.add(0)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings.toLongArray(), -1)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MuscleCommScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MuscleCommScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val isListening by viewModel.isListening.collectAsState()
    val threshold by viewModel.threshold.collectAsState()
    val currentCount by viewModel.currentCount.collectAsState()
    val tokens by viewModel.tokens.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val currentMagnitude by viewModel.currentMagnitude.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Pocket Sensor".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Haptic Pulse",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Toggle listening button instead of the dot
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = CircleShape
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { viewModel.toggleListening() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.primary else Color.DarkGray,
                            CircleShape
                        )
                )
            }
        }

        // Main content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Buffer circle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentCount.toString(),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "CURRENT BUFFER",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 2.sp
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Simple visualization of magnitude
                    LinearProgressIndicator(
                        progress = { (currentMagnitude / 10f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.5f).height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            // Two cards row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Card: Detected Movement
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "DETECTED MOVEMENT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp).clickable { viewModel.clearTokens() }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = if (tokens.isEmpty()) "..." else tokens.joinToString(" "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = viewModel.decodeTokens(tokens).ifEmpty { "Ready" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Right Card: Haptic Feedback
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .clickable { viewModel.simulateResponse() }
                        .padding(16.dp)
                ) {
                    Text(
                        text = "HAPTIC FEEDBACK",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Simulate",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Vibrate 2, 3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Session history
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(
                        text = "Session History",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        
        // Settings / Threshold area at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Threshold",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = threshold,
                onValueChange = { viewModel.setThreshold(it) },
                valueRange = 0.5f..10.0f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
