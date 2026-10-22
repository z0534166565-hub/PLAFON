@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.kosherdialer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Green = Color(0xFF1E8E3E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@SuppressLint("MissingPermission")
class PhoneVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("p", 0)
    private val pbFile = File(app.filesDir, "pb.vcf")
    private val cchFile = File(app.filesDir, "cch.vcf")
    var contacts by mutableStateOf<List<Contact>>(emptyList())
    var calls by mutableStateOf<List<CallEntry>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var deviceAddr by mutableStateOf(prefs.getString("dev", null))

    init {
        if (pbFile.exists()) contacts = parseContacts(pbFile.readText())
        if (cchFile.exists()) calls = parseCalls(cchFile.readText())
    }

    private val adapter get() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    fun pairedDevices(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }

    fun select(d: BluetoothDevice) {
        deviceAddr = d.address; prefs.edit().putString("dev", d.address).apply(); refresh()
    }

    fun nameFor(number: String): String? {
        val k = norm(number); if (k.length < 5) return null
        return contacts.firstOrNull { c -> c.numbers.any { norm(it) == k } }?.name
    }

    fun refresh() {
        val addr = deviceAddr ?: return
        val ad = adapter ?: return
        viewModelScope.launch {
            loading = true; error = null
            try {
                val (pb, cch) = withContext(Dispatchers.IO) {
                    PbapSession(ad.getRemoteDevice(addr)).use { s ->
                        s.open()
                        val pb = s.pull("telecom/pb.vcf", 65535)
                        val cch = try { s.pull("telecom/cch.vcf", 200) } catch (_: Exception) { "" }
                        pb to cch
                    }
                }
                contacts = parseContacts(pb); pbFile.writeText(pb)
                if (cch.isNotEmpty()) { calls = parseCalls(cch); cchFile.writeText(cch) }
            } catch (e: Exception) {
                error = "לא הצלחתי למשוך נתונים מהמכשיר. ודא שהמכשיר מחובר ושאישרת שיתוף אנשי קשר (${e.message})"
            }
            loading = false
        }
    }
}

/** מחייג דרך מכשיר האנדרואיד עצמו - נפתח מסך השיחה הרגיל של המערכת, כולל רמקול. */
fun placeCall(ctx: Context, number: String) {
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    ctx.startActivity(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme() else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Root() }
}

@Composable
fun Root(vm: PhoneVm = viewModel()) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var number by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val perms = buildList {
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (vm.deviceAddr == null) picker = true else vm.refresh()
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("חיוג", "אחרונות", "אנשי קשר")[tab]) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "רענן") }
                    IconButton(onClick = { picker = true }) { Icon(Icons.Default.Bluetooth, "בחר מכשיר") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Dialpad, null) }, label = { Text("חיוג") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.History, null) }, label = { Text("אחרונות") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Person, null) }, label = { Text("אנשי קשר") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), fontSize = 13.sp) }
            when (tab) {
                0 -> Keypad(number, { number = it }, vm.nameFor(number)) { if (number.isNotEmpty()) placeCall(ctx, number) }
                1 -> Recents(vm) { placeCall(ctx, it) }
                else -> ContactsTab(vm) { placeCall(ctx, it) }
            }
        }
    }
    if (picker) DevicePicker(vm) { picker = false }
}

@SuppressLint("MissingPermission")
@Composable
fun DevicePicker(vm: PhoneVm, onDismiss: () -> Unit) {
    val devices = vm.pairedDevices()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } },
        title = { Text("בחר מכשיר מחובר") },
        text = {
            if (devices.isEmpty()) Text("אין מכשירים מצומדים. צמד את המכשיר בהגדרות הבלוטות' ואז חזור לכאן.")
            else LazyColumn {
                items(devices) { d ->
                    val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(d.address) },
                        trailingContent = { if (d.address == vm.deviceAddr) Icon(Icons.Default.Check, null) },
                        modifier = Modifier.clickable { vm.select(d); onDismiss() }
                    )
                }
            }
        }
    )
}

@Composable
fun Keypad(number: String, onChange: (String) -> Unit, match: String?, onCall: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(match ?: " ", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(number, fontSize = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { ch ->
                        Box(
                            Modifier.size(80.dp).clip(CircleShape).clickable { onChange(number + ch) },
                            contentAlignment = Alignment.Center
                        ) { Text(ch.toString(), fontSize = 32.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                Spacer(Modifier.size(80.dp))
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Green).clickable(onClick = onCall),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Call, "חייג", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Box(
                    Modifier.size(80.dp).clip(CircleShape).combinedClickable(
                        onClick = { onChange(number.dropLast(1)) }, onLongClick = { onChange("") }),
                    contentAlignment = Alignment.Center
                ) { if (number.isNotEmpty()) Icon(Icons.AutoMirrored.Filled.Backspace, "מחק") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun Avatar(label: String) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val c = label.firstOrNull { it.isLetter() }
        if (c != null) Text(c.uppercase(), fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        else Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun Recents(vm: PhoneVm, onCall: (String) -> Unit) {
    if (vm.calls.isEmpty()) { Empty("אין שיחות להצגה"); return }
    LazyColumn {
        items(vm.calls) { c ->
            val name = c.name.ifEmpty { vm.nameFor(c.number) ?: "" }
            val missed = c.type == "MISSED"
            ListItem(
                leadingContent = { Avatar(name) },
                headlineContent = { Text(name.ifEmpty { c.number }, color = if (missed) MaterialTheme.colorScheme.error else Color.Unspecified) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tint) = when (c.type) {
                            "MISSED" -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.error
                            "DIALED" -> Icons.AutoMirrored.Filled.CallMade to Green
                            else -> Icons.AutoMirrored.Filled.CallReceived to Green
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(fmtTime(c.time))
                    }
                },
                trailingContent = { Icon(Icons.Default.Call, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onCall(c.number) }
            )
        }
    }
}

@Composable
fun ContactsTab(vm: PhoneVm, onCall: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Contact?>(null) }
    val list = vm.contacts.filter { q.isBlank() || it.name.contains(q, true) || it.numbers.any { n -> n.contains(q) } }
    Column {
        OutlinedTextField(
            q, { q = it }, singleLine = true, shape = CircleShape,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("חיפוש אנשי קשר") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (list.isEmpty()) { Empty("אין אנשי קשר להצגה"); return }
        LazyColumn {
            items(list) { c ->
                ListItem(
                    leadingContent = { Avatar(c.name) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(c.numbers.firstOrNull() ?: "") },
                    modifier = Modifier.clickable { if (c.numbers.size == 1) onCall(c.numbers[0]) else if (c.numbers.size > 1) chosen = c }
                )
            }
        }
    }
    chosen?.let { c ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text("ביטול") } },
            title = { Text(c.name) },
            text = {
                Column {
                    c.numbers.forEach { n ->
                        ListItem(headlineContent = { Text(n) }, modifier = Modifier.clickable { chosen = null; onCall(n) })
                    }
                }
            }
        )
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
