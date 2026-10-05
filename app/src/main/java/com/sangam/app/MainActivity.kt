package com.sangam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sangam.app.community.IntroStatus
import com.sangam.app.ui.ChatScreen
import com.sangam.app.ui.DiscoverScreen
import com.sangam.app.ui.InboxScreen
import com.sangam.app.ui.LedgerScreen
import com.sangam.app.ui.MeScreen
import com.sangam.app.ui.ProjectsScreen
import com.sangam.app.ui.River
import com.sangam.app.ui.RoomScreen
import com.sangam.app.ui.SangamTheme
import com.sangam.app.ui.SangamViewModel
import com.sangam.app.ui.WallScreen

class MainActivity : ComponentActivity() {
    private val vm: SangamViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.restartMesh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needed = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())

        setContent {
            SangamTheme {
                val msg by vm.message.collectAsState()
                LaunchedEffect(msg) { msg?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show(); vm.messageShown() } }
                val nav = rememberNavController()
                val start = androidx.compose.runtime.remember { if (vm.profile.value.isBlank) "me" else "room" }
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                val intros by vm.intros.collectAsState()
                val pending = intros.count { it.incoming && it.status == IntroStatus.PENDING }
                val tabs = listOf("room" to "Room", "discover" to "Discover", "projects" to "Projects", "inbox" to "Inbox", "me" to "Me")
                Column(Modifier.fillMaxSize().background(River.mist).safeDrawingPadding()) {
                    Box(Modifier.weight(1f)) {
                        NavHost(nav, startDestination = start) {
                            composable("room") { RoomScreen(vm, onWall = { nav.navigate("wall") }, onLedger = { nav.navigate("ledger") }) }
                            composable("discover") { DiscoverScreen(vm) }
                            composable("projects") { ProjectsScreen(vm) }
                            composable("inbox") { InboxScreen(vm) { conv, peer -> nav.navigate("chat/$conv/$peer") } }
                            composable("me") { MeScreen(vm) }
                            composable("wall") { WallScreen(vm) { nav.popBackStack() } }
                            composable("ledger") { LedgerScreen(vm) { nav.popBackStack() } }
                            composable("chat/{conv}/{peer}") { e ->
                                ChatScreen(vm, e.arguments?.getString("conv").orEmpty(), e.arguments?.getString("peer")) { nav.popBackStack() }
                            }
                        }
                    }
                    if (route in tabs.map { it.first }) {
                        NavigationBar(containerColor = River.paper) {
                            tabs.forEach { (r, label) ->
                                NavigationBarItem(
                                    selected = route == r,
                                    onClick = {
                                        nav.navigate(r) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        if (r == "inbox" && pending > 0) BadgedBox(badge = { Badge { Text("$pending") } }) { Text("●") } else Text("●", color = if (route == r) River.marigold else River.line)
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
