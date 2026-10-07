package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.navigation.Screen

/**
 * Reusable sleek studio card with 24.dp heavily rounded corners and a soft-glowing dual edge.
 */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = Color(0xFF131318),
    borderGradientTop: Color = Color(0xFF3E3E50),
    borderGradientBottom: Color = Color(0xFF242430),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = Color(0x33A0A0B8),
                spotColor = Color(0x22FFFFFF)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            1.2.dp,
            Brush.verticalGradient(
                listOf(borderGradientTop, borderGradientBottom)
            )
        ),
        content = content
    )
}

/**
 * Custom Dropdown matching the reference:
 * Generously rounded (16.dp), glowing subtle edge, with a solid white pill for the selected option.
 */
@Composable
fun <T> StudioDropdown(
    label: String? = null,
    icon: ImageVector? = null,
    selectedValue: T,
    items: List<T>,
    itemTitle: (T) -> String,
    itemSubtitle: ((T) -> String)? = null,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = "studio_dropdown"
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!label.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 2.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF8E8E98),
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    color = Color(0xFF8E8E98)
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Trigger Box with 16.dp rounded corners & soft glow border
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        triggerWidth = coordinates.size.width
                    }
                    .shadow(
                        elevation = if (expanded) 6.dp else 2.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = Color(0x26FFFFFF),
                        spotColor = Color(0x1AFFFFFF)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .testTag("${testTag}_trigger"),
                color = Color(0xFF111116),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.2.dp,
                    if (expanded) {
                        Brush.verticalGradient(listOf(Color(0xFF5E5E76), Color(0xFF38384A)))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF363644), Color(0xFF202028)))
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = itemTitle(selectedValue),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = Color(0xFFB0B0C0),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(arrowRotation)
                    )
                }
            }

            // Dropdown Menu matching exact trigger width with 18.dp rounded corners
            val menuWidthDp = with(density) { triggerWidth.toDp() }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .then(if (menuWidthDp > 0.dp) Modifier.width(menuWidthDp) else Modifier.fillMaxWidth())
                    .background(Color(0xFF131319))
                    .shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x40FFFFFF), spotColor = Color(0x30FFFFFF))
                    .border(
                        BorderStroke(
                            1.2.dp,
                            Brush.verticalGradient(listOf(Color(0xFF48485E), Color(0xFF282836)))
                        ),
                        RoundedCornerShape(18.dp)
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .padding(6.dp)
                    .testTag("${testTag}_menu")
            ) {
                items.forEach { item ->
                    val isSelected = item == selectedValue
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable {
                                onItemSelected(item)
                                expanded = false
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                            .testTag("${testTag}_item_${itemTitle(item)}")
                    ) {
                        Column {
                            Text(
                                text = itemTitle(item),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color(0xFFE2E2EA),
                                fontSize = 13.sp
                            )
                            if (itemSubtitle != null) {
                                val sub = itemSubtitle(item)
                                if (sub.isNotBlank()) {
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color(0xFF444444) else Color(0xFF888894)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * High contrast studio switch matching the reference
 */
@Composable
fun StudioSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF000000),
            checkedTrackColor = Color(0xFFFFFFFF),
            checkedBorderColor = Color(0xFFFFFFFF),
            uncheckedThumbColor = Color(0xFFFFFFFF),
            uncheckedTrackColor = Color(0xFF202028),
            uncheckedBorderColor = Color(0xFF3A3A4A)
        ),
        modifier = modifier
    )
}

/**
 * Hardware-grade, ultra-sleek bottom navigation dock.
 * Completely eliminates bulky M3 oval pills with a refined micro-accent indicator
 * and tactile, low-profile studio aesthetic.
 */
@Composable
fun StudioBottomNavigation(
    screens: List<Screen>,
    currentRoute: String?,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_nav_bar"),
        color = Color(0xFF0C0C10),
        border = BorderStroke(1.dp, Color(0xFF1E1E28))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            screens.forEach { screen ->
                val isSelected = currentRoute == screen.route
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color(0xFF6E6E82),
                    label = "iconColor"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color(0xFF6E6E82),
                    label = "textColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = Color.White.copy(alpha = 0.12f))
                        ) {
                            onScreenSelected(screen)
                        }
                        .testTag("nav_tab_${screen.route}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Top sleek micro accent bar
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSelected) Color.White else Color.Transparent)
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            tint = iconColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = screen.title.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.8.sp,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

