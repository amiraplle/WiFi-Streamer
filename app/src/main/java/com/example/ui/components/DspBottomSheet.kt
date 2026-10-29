package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.UserPreferences
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DspModalBottomSheet(
    onDismiss: () -> Unit,
    prefs: UserPreferences,
    onDspEnabledChange: (Boolean) -> Unit,
    onSoftLimiterChange: (Boolean) -> Unit,
    onLimiterTrimChange: (Float) -> Unit,
    onAirBandEnabledChange: (Boolean) -> Unit,
    onAirBandGainChange: (Float) -> Unit,
    onBassPunchEnabledChange: (Boolean) -> Unit,
    onBassPunchIntensityChange: (Int) -> Unit,
    onSubBassCutChange: (Boolean) -> Unit,
    onStereoWidenerChange: (Boolean) -> Unit,
    onStereoWidthChange: (Int) -> Unit,
    onOpenFullSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121218),
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3E3E4E))
            )
        },
        modifier = modifier
    ) {
        RealTimeDspBottomSheetContent(
            prefs = prefs,
            onDspEnabledChange = onDspEnabledChange,
            onSoftLimiterChange = onSoftLimiterChange,
            onLimiterTrimChange = onLimiterTrimChange,
            onAirBandEnabledChange = onAirBandEnabledChange,
            onAirBandGainChange = onAirBandGainChange,
            onBassPunchEnabledChange = onBassPunchEnabledChange,
            onBassPunchIntensityChange = onBassPunchIntensityChange,
            onSubBassCutChange = onSubBassCutChange,
            onStereoWidenerChange = onStereoWidenerChange,
            onStereoWidthChange = onStereoWidthChange,
            onOpenFullSettings = onOpenFullSettings
        )
    }
}

@Composable
fun RealTimeDspBottomSheetContent(
    prefs: UserPreferences,
    onDspEnabledChange: (Boolean) -> Unit,
    onSoftLimiterChange: (Boolean) -> Unit,
    onLimiterTrimChange: (Float) -> Unit,
    onAirBandEnabledChange: (Boolean) -> Unit,
    onAirBandGainChange: (Float) -> Unit,
    onBassPunchEnabledChange: (Boolean) -> Unit,
    onBassPunchIntensityChange: (Int) -> Unit,
    onSubBassCutChange: (Boolean) -> Unit,
    onStereoWidenerChange: (Boolean) -> Unit,
    onStereoWidthChange: (Int) -> Unit,
    onOpenFullSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF1E1E28)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "DSP",
                        tint = if (prefs.dspEnabled) StreamEmerald else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Real-Time DSP Tweaks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (prefs.dspEnabled) "Live 64-bit PCM audio filtering active" else "DSP engine is currently bypassed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prefs.dspEnabled) StreamEmerald else TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Switch(
                checked = prefs.dspEnabled,
                onCheckedChange = onDspEnabledChange,
                modifier = Modifier.testTag("modal_dsp_master_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StreamEmerald,
                    uncheckedThumbColor = Color(0xFF888898),
                    uncheckedTrackColor = Color(0xFF22222E)
                )
            )
        }

        HorizontalDivider(color = Color(0xFF22222E))

        // 1. Air Band High-Shelf (14 kHz)
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.airBandEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Waves, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Air Band (14 kHz)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Silky shimmer & open air without sibilance", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.airBandEnabled,
                        onCheckedChange = onAirBandEnabledChange,
                        modifier = Modifier.testTag("modal_air_band_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.airBandEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Gain", fontSize = 11.sp, color = TextSecondary)
                        Slider(
                            value = prefs.airBandGainDb,
                            onValueChange = onAirBandGainChange,
                            valueRange = 0f..10f,
                            steps = 19,
                            modifier = Modifier.weight(1f).testTag("modal_air_band_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        Text("+${String.format("%.1f", prefs.airBandGainDb)} dB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 2. Tight Bass Punch & Mud-Cut
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.bassPunchEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Tight Bass Punch & Mud-Cut", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("65 Hz kick snap + 280 Hz boxy mud notch", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.bassPunchEnabled,
                        onCheckedChange = onBassPunchEnabledChange,
                        modifier = Modifier.testTag("modal_bass_punch_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.bassPunchEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Punch", fontSize = 11.sp, color = TextSecondary)
                        Slider(
                            value = prefs.bassPunchIntensity.toFloat(),
                            onValueChange = { onBassPunchIntensityChange(it.toInt()) },
                            valueRange = 0f..100f,
                            steps = 19,
                            modifier = Modifier.weight(1f).testTag("modal_bass_punch_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        val boostDb = (prefs.bassPunchIntensity / 100f) * 12f
                        Text("+${String.format("%.1f", boostDb)} dB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 3. Sub-Bass Rumble Cut (28 Hz)
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.subBassCutEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Sub-Bass Rumble Cut (28 Hz)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Saves DAC headroom & stops small cone flutter", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                    }
                }
                Switch(
                    checked = prefs.subBassCutEnabled,
                    onCheckedChange = onSubBassCutChange,
                    modifier = Modifier.testTag("modal_sub_bass_cut_switch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                )
            }
        }

        // 4. Split-Band Stereo Widener
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.stereoWidenerEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.SurroundSound, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Split-Band Stereo Widener", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Mono sub-150Hz bass + wide high soundstage", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.stereoWidenerEnabled,
                        onCheckedChange = onStereoWidenerChange,
                        modifier = Modifier.testTag("modal_stereo_widener_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.stereoWidenerEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Width", fontSize = 11.sp, color = TextSecondary)
                        Slider(
                            value = prefs.stereoWidthPercent.toFloat(),
                            onValueChange = { onStereoWidthChange(it.toInt()) },
                            valueRange = 100f..180f,
                            modifier = Modifier.weight(1f).testTag("modal_stereo_width_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        Text("${prefs.stereoWidthPercent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 5. Soft-Knee Peak Limiter & Headroom Trim
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.softLimiterEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Compress, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Soft-Knee Peak Limiter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Anti-clipping protection at -1.36 dBFS", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.softLimiterEnabled,
                        onCheckedChange = onSoftLimiterChange,
                        modifier = Modifier.testTag("modal_soft_limiter_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Trim", fontSize = 11.sp, color = TextSecondary)
                    Slider(
                        value = prefs.limiterTrimDb,
                        onValueChange = onLimiterTrimChange,
                        valueRange = -12f..8f,
                        steps = 39,
                        modifier = Modifier.weight(1f).testTag("modal_limiter_trim_slider"),
                        colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                    )
                    Surface(
                        color = Color(0xFF1F1F2C),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onLimiterTrimChange(0f) }
                    ) {
                        Text(
                            text = if (prefs.limiterTrimDb > 0f) "+${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                   else if (prefs.limiterTrimDb < 0f) "${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                   else "0 dB",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (prefs.limiterTrimDb != 0f) StreamEmerald else Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Link to Settings
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth().testTag("modal_open_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Full DSP Settings", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
