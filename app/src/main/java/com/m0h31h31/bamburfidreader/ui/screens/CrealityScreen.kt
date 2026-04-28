package com.m0h31h31.bamburfidreader.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.CrealityMaterial
import com.m0h31h31.bamburfidreader.CrealityTagData
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CREALITY_WEIGHT_OPTIONS = listOf("1 KG", "750 G", "600 G", "500 G", "250 G")

// ---------------------------------------------------------------------------
// Camera color picker dialog
// ---------------------------------------------------------------------------

@Composable
fun CameraColorPickerDialog(
    bitmap: Bitmap,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    var pickedHex by remember { mutableStateOf("") }
    var pickedColor by remember { mutableStateOf<Color?>(null) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.creality_camera_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 颜色预览 / 提示
                if (pickedColor != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(pickedColor!!)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        )
                        Text(
                            text = "#$pickedHex",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.creality_camera_color_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 图片 + 点选覆盖层
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val bx = ((offset.x / w) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                    val by = ((offset.y / h) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                    val pixel = bitmap.getPixel(bx, by)
                                    val r = android.graphics.Color.red(pixel)
                                    val g = android.graphics.Color.green(pixel)
                                    val b = android.graphics.Color.blue(pixel)
                                    pickedHex = String.format("%02X%02X%02X", r, g, b)
                                    pickedColor = Color(r / 255f, g / 255f, b / 255f)
                                    tapOffset = offset
                                }
                            }
                    ) {
                        tapOffset?.let { center ->
                            drawCircle(
                                color = Color.White,
                                radius = 10.dp.toPx(),
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.45f),
                                radius = 12.dp.toPx(),
                                center = center,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pickedHex.isNotEmpty()) onColorSelected(pickedHex) },
                enabled = pickedHex.isNotEmpty()
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

// ---------------------------------------------------------------------------
// Color picker dialog
// ---------------------------------------------------------------------------

@Composable
fun ColorPickerDialog(
    initialHex: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initHsv = remember(initialHex) {
        val cleanHex = initialHex.trimStart('#').padEnd(6, '0').take(6)
        val colorInt = try {
            android.graphics.Color.parseColor("#$cleanHex")
        } catch (_: Exception) {
            android.graphics.Color.RED
        }
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colorInt, hsv)
        hsv
    }

    var hue by remember { mutableStateOf(initHsv[0]) }
    var saturation by remember { mutableStateOf(initHsv[1]) }
    var value by remember { mutableStateOf(initHsv[2]) }
    var hexInput by remember {
        mutableStateOf(initialHex.trimStart('#').padEnd(6, '0').take(6).uppercase())
    }
    var hexInputDirty by remember { mutableStateOf(false) }

    val currentColorInt by remember(hue, saturation, value) {
        derivedStateOf { android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) }
    }
    val currentColor by remember(currentColorInt) {
        derivedStateOf { Color(currentColorInt) }
    }

    LaunchedEffect(currentColorInt) {
        if (!hexInputDirty) {
            hexInput = String.format("%06X", currentColorInt and 0xFFFFFF)
        }
    }

    val hueColor by remember(hue) {
        derivedStateOf { Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.creality_color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Color preview bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                )

                // 2D SV picker
                var svBoxSize by remember { mutableStateOf(Size.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val w = svBoxSize.width.coerceAtLeast(1f)
                                        val h = svBoxSize.height.coerceAtLeast(1f)
                                        saturation = (offset.x / w).coerceIn(0f, 1f)
                                        value = (1f - offset.y / h).coerceIn(0f, 1f)
                                        hexInputDirty = false
                                    },
                                    onDrag = { change, _ ->
                                        val w = svBoxSize.width.coerceAtLeast(1f)
                                        val h = svBoxSize.height.coerceAtLeast(1f)
                                        saturation = (change.position.x / w).coerceIn(0f, 1f)
                                        value = (1f - change.position.y / h).coerceIn(0f, 1f)
                                        hexInputDirty = false
                                    }
                                )
                            }
                    ) {
                        svBoxSize = this.size
                        val w = size.width
                        val h = size.height
                        drawRect(color = hueColor, size = size)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, Color.Transparent),
                                startX = 0f, endX = w
                            ),
                            size = size
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                startY = 0f, endY = h
                            ),
                            size = size
                        )
                        val thumbX = saturation * w
                        val thumbY = (1f - value) * h
                        val thumbRadius = 10.dp.toPx()
                        drawCircle(color = Color.White, radius = thumbRadius, center = Offset(thumbX, thumbY))
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.4f),
                            radius = thumbRadius,
                            center = Offset(thumbX, thumbY),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // Hue slider
                Text(
                    text = stringResource(R.string.creality_label_hue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val hueColors = listOf(
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(0f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(60f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(120f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(180f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(240f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(300f, 1f, 1f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(360f, 1f, 1f)))
                        )
                        drawRect(brush = Brush.horizontalGradient(colors = hueColors), size = size)
                    }
                    Slider(
                        value = hue,
                        onValueChange = { newHue -> hue = newHue; hexInputDirty = false },
                        valueRange = 0f..360f,
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    )
                }

                // Hex input
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        val filtered = raw.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        hexInput = filtered
                        hexInputDirty = filtered.length < 6
                        if (filtered.length == 6) {
                            try {
                                val colorInt = android.graphics.Color.parseColor("#$filtered")
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(colorInt, hsv)
                                hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                hexInputDirty = false
                            } catch (_: Exception) {}
                        }
                    },
                    label = { Text(stringResource(R.string.creality_hex_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    prefix = { Text("#", fontFamily = FontFamily.Monospace) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hex = String.format("%06X", currentColorInt and 0xFFFFFF)
                onColorSelected(hex)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

// ---------------------------------------------------------------------------
// Reusable dropdown selector
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelector(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main screen composable
// ---------------------------------------------------------------------------

@Composable
fun CrealityScreen(
    dbHelper: FilamentDbHelper?,
    crealityTagData: CrealityTagData?,
    crealityStatusMessage: String,
    writeInProgress: Boolean,
    onPrepareWrite: (materialId: String, colorHex: String, weight: String) -> Unit,
    onCancelWrite: () -> Unit,
    onClearTagData: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ---- Unified form state ----
    var brands by remember { mutableStateOf<List<String>>(emptyList()) }
    var types by remember { mutableStateOf<List<String>>(emptyList()) }
    var materials by remember { mutableStateOf<List<CrealityMaterial>>(emptyList()) }

    var selectedBrand by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var selectedMaterialId by remember { mutableStateOf("") }
    var selectedMaterialName by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf("FF0000") }
    var selectedWeight by remember { mutableStateOf(CREALITY_WEIGHT_OPTIONS[0]) }
    var showColorPicker by remember { mutableStateOf(false) }
    var cameraBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showCameraColorPicker by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            cameraBitmap = bitmap
            showCameraColorPicker = true
        }
    }

    // Load brands on first composition
    LaunchedEffect(dbHelper) {
        if (dbHelper != null) {
            val loaded = withContext(Dispatchers.IO) {
                dbHelper.getCrealityBrands(dbHelper.readableDatabase)
            }
            brands = loaded
            if (loaded.isNotEmpty() && selectedBrand.isEmpty()) {
                selectedBrand = loaded[0]
            }
        }
    }

    // Load types when brand changes; keep existing selectedType if it's still valid
    LaunchedEffect(selectedBrand, dbHelper) {
        if (selectedBrand.isNotEmpty() && dbHelper != null) {
            val loaded = withContext(Dispatchers.IO) {
                dbHelper.getCrealityTypes(dbHelper.readableDatabase, selectedBrand)
            }
            types = loaded
            if (selectedType !in loaded) {
                selectedType = loaded.firstOrNull() ?: ""
            }
        } else {
            types = emptyList()
            selectedType = ""
        }
    }

    // Load materials when brand/type changes; keep existing selection if still valid
    LaunchedEffect(selectedBrand, selectedType, dbHelper) {
        if (selectedBrand.isNotEmpty() && selectedType.isNotEmpty() && dbHelper != null) {
            val loaded = withContext(Dispatchers.IO) {
                dbHelper.getCrealityMaterials(dbHelper.readableDatabase, selectedBrand, selectedType)
            }
            materials = loaded
            val existing = loaded.firstOrNull { it.materialId == selectedMaterialId }
            if (existing != null) {
                selectedMaterialName = existing.name
            } else {
                selectedMaterialId = loaded.firstOrNull()?.materialId ?: ""
                selectedMaterialName = loaded.firstOrNull()?.name ?: ""
            }
        } else {
            materials = emptyList()
            selectedMaterialId = ""
            selectedMaterialName = ""
        }
    }

    // Auto-fill form when a tag is read
    LaunchedEffect(crealityTagData) {
        val data = crealityTagData ?: return@LaunchedEffect
        if (dbHelper != null) {
            val mat = withContext(Dispatchers.IO) {
                dbHelper.getCrealityMaterialById(dbHelper.readableDatabase, data.materialId)
            }
            if (mat != null) {
                // Set all fields before cascading effects fire so they see the target values
                selectedBrand = mat.brand
                selectedType = mat.materialType
                selectedMaterialId = mat.materialId
                selectedMaterialName = mat.name
            }
        }
        selectedColorHex = data.colorHex
        selectedWeight = data.weight
    }

    val canWrite = selectedMaterialId.isNotEmpty() && selectedWeight.isNotEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.creality_page_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.creality_page_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Unified read/write form ----
        item {
            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Brand dropdown
                    DropdownSelector(
                        label = stringResource(R.string.creality_label_brand),
                        selected = selectedBrand.ifEmpty { stringResource(R.string.creality_select_brand) },
                        options = brands,
                        onSelected = { selectedBrand = it },
                        enabled = brands.isNotEmpty() && !writeInProgress
                    )

                    // Type dropdown
                    DropdownSelector(
                        label = stringResource(R.string.creality_label_type),
                        selected = selectedType.ifEmpty { stringResource(R.string.creality_select_type) },
                        options = types,
                        onSelected = { selectedType = it },
                        enabled = selectedBrand.isNotEmpty() && types.isNotEmpty() && !writeInProgress
                    )

                    // Material dropdown
                    DropdownSelector(
                        label = stringResource(R.string.creality_label_name),
                        selected = selectedMaterialName.ifEmpty { stringResource(R.string.creality_select_material) },
                        options = materials.map { it.name },
                        onSelected = { name ->
                            val mat = materials.firstOrNull { it.name == name }
                            if (mat != null) {
                                selectedMaterialId = mat.materialId
                                selectedMaterialName = mat.name
                            }
                        },
                        enabled = selectedType.isNotEmpty() && materials.isNotEmpty() && !writeInProgress
                    )

                    // Color row
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.creality_label_color),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val colorInt = remember(selectedColorHex) {
                                try { android.graphics.Color.parseColor("#$selectedColorHex") }
                                catch (_: Exception) { android.graphics.Color.RED }
                            }
                            Box(
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(colorInt))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable(enabled = !writeInProgress) { showColorPicker = true }
                            )
                            Text(
                                text = "#${selectedColorHex.uppercase()}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { showColorPicker = true },
                                enabled = !writeInProgress,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.creality_btn_pick_color),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            OutlinedButton(
                                onClick = { cameraLauncher.launch(null) },
                                enabled = !writeInProgress,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.creality_btn_camera_color),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    // Weight dropdown
                    DropdownSelector(
                        label = stringResource(R.string.creality_label_weight),
                        selected = selectedWeight.ifEmpty { stringResource(R.string.creality_select_weight) },
                        options = CREALITY_WEIGHT_OPTIONS,
                        onSelected = { selectedWeight = it },
                        enabled = !writeInProgress
                    )
                }
            }
        }

        // ---- Status + Action ----
        item {
            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status message
                    if (crealityStatusMessage.isNotEmpty()) {
                        Text(
                            text = crealityStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(8.dp)
                        )
                    }

                    if (writeInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.creality_write_ready),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = onCancelWrite,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(stringResource(R.string.creality_btn_cancel_write))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.creality_write_scan_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onPrepareWrite(selectedMaterialId, selectedColorHex, selectedWeight) },
                            enabled = canWrite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.creality_btn_prepare_write))
                        }
                    }
                }
            }
        }

        // ---- Creality logo 点缀 ----
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.creality_logo_black),
                    contentDescription = null,
                    modifier = Modifier
                        .height(56.dp)
                        .alpha(0.18f),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }

    // Color picker dialog
    if (showColorPicker) {
        ColorPickerDialog(
            initialHex = selectedColorHex,
            onColorSelected = { hex -> selectedColorHex = hex; showColorPicker = false },
            onDismiss = { showColorPicker = false }
        )
    }

    // Camera color picker dialog
    val bmp = cameraBitmap
    if (showCameraColorPicker && bmp != null) {
        CameraColorPickerDialog(
            bitmap = bmp,
            onColorSelected = { hex -> selectedColorHex = hex; showCameraColorPicker = false },
            onDismiss = { showCameraColorPicker = false }
        )
    }
}
