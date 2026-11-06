package com.veluna.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.veluna.LocalPlayerAwareWindowInsets
import com.veluna.R

import com.veluna.ui.component.IconButton
import com.veluna.ui.component.Material3SettingsGroup
import com.veluna.ui.component.Material3SettingsItem
import com.veluna.ui.screens.Screens
import com.veluna.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  highlightKey: String? = null
) {
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current
  val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

  var searchQuery by rememberSaveable { mutableStateOf("") }
  val searchLower = searchQuery.lowercase()

  val accountText = stringResource(R.string.account)
  val appearanceText = stringResource(R.string.appearance)
  val playerText = stringResource(R.string.player_and_audio)
  val listenTogetherText = stringResource(R.string.listen_together)
  val contentText = stringResource(R.string.content)
  val privacyText = stringResource(R.string.privacy)
  val storageText = stringResource(R.string.storage)
  val backupText = stringResource(R.string.backup_restore)
  val aboutText = stringResource(R.string.about)

  val accountDesc = stringResource(R.string.setting_desc_account)
  val appearanceDesc = stringResource(R.string.setting_desc_appearance)
  val playerDesc = stringResource(R.string.setting_desc_player)
  val listenTogetherDesc = stringResource(R.string.setting_desc_listen_together)
  val contentDesc = stringResource(R.string.setting_desc_content)
  val privacyDesc = stringResource(R.string.setting_desc_privacy)
  val storageDesc = stringResource(R.string.setting_desc_storage)
  val backupDesc = stringResource(R.string.setting_desc_backup)
  val aboutDesc = stringResource(R.string.setting_desc_about)

  val scrollState = rememberScrollState()
  Column(
    Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
      )
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp)
  ) {
    Spacer(
      Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    )
    Text(
      text = stringResource(R.string.settings),
      style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp)
    )

    TextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = { Text(stringResource(R.string.search)) },
      leadingIcon = {
        Icon(
          imageVector = Icons.Rounded.Search,
          contentDescription = stringResource(R.string.search)
        )
      },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { searchQuery = "" }) {
            Icon(imageVector = Icons.Rounded.Clear, contentDescription = "Clear")
          }
        }
      },
      shape = RoundedCornerShape(28.dp),
      colors =
        TextFieldDefaults.colors(
          focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
          unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
          disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        ),
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
    )

    val itemsList = buildList {
      if (
        accountText.lowercase().contains(searchLower) ||
          accountDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == accountText),
            icon = painterResource(R.drawable.account),
            title = { Text(accountText) },
            description = { Text(accountDesc) },
            onClick = { navController.navigate("settings/account") }
          )
        )
      }


      if ("listening summary".contains(searchLower)) {
        add(
          Material3SettingsItem(
            icon = painterResource(com.veluna.R.drawable.history),
            title = { Text(stringResource(com.veluna.R.string.listening_summary)) },
            description = {
              Text(stringResource(com.veluna.R.string.listening_summary_desc))
            },
            onClick = { navController.navigate("settings/listening_summary") }
          )
        )
      }

      if (
        appearanceText.lowercase().contains(searchLower) ||
          appearanceDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == appearanceText),
            icon = painterResource(R.drawable.palette),
            title = { Text(appearanceText) },
            description = { Text(appearanceDesc) },
            onClick = { navController.navigate("settings/appearance") }
          )
        )
      }
      if (
        playerText.lowercase().contains(searchLower) || playerDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == playerText),
            icon = painterResource(R.drawable.play),
            title = { Text(playerText) },
            description = { Text(playerDesc) },
            onClick = { navController.navigate("settings/player") }
          )
        )
      }
      if (
        listenTogetherText.lowercase().contains(searchLower) ||
          listenTogetherDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == listenTogetherText),
            icon = painterResource(R.drawable.group),
            title = { Text(listenTogetherText) },
            description = { Text(listenTogetherDesc) },
            onClick = { navController.navigate(Screens.ListenTogether.route) }
          )
        )
      }
      if (
        contentText.lowercase().contains(searchLower) ||
          contentDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == contentText),
            icon = painterResource(R.drawable.language),
            title = { Text(contentText) },
            description = { Text(contentDesc) },
            onClick = { navController.navigate("settings/content") }
          )
        )
      }



      if (
        privacyText.lowercase().contains(searchLower) ||
          privacyDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == privacyText),
            icon = painterResource(R.drawable.security),
            title = { Text(privacyText) },
            description = { Text(privacyDesc) },
            onClick = { navController.navigate("settings/privacy") }
          )
        )
      }
      if (
        storageText.lowercase().contains(searchLower) ||
          storageDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == storageText),
            icon = painterResource(R.drawable.storage),
            title = { Text(storageText) },
            description = { Text(storageDesc) },
            onClick = { navController.navigate("settings/storage") }
          )
        )
      }


      if (
        backupText.lowercase().contains(searchLower) || backupDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == backupText),
            icon = painterResource(R.drawable.restore),
            title = { Text(backupText) },
            description = { Text(backupDesc) },
            onClick = { navController.navigate("settings/backup_restore") }
          )
        )
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if ("supported links".contains(searchLower)) {
          add(
            Material3SettingsItem(
              isHighlighted = (highlightKey == "supported links"),
              icon = painterResource(R.drawable.link),
              title = { Text("Supported Links") },
              description = { Text("App linking settings") },
              onClick = {
                try {
                  val intent =
                    Intent(
                      Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                      Uri.parse("package:${context.packageName}")
                    )
                  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  context.startActivity(intent)
                } catch (e: Exception) {
                  when (e) {
                    is ActivityNotFoundException,
                    is SecurityException -> {
                      Toast.makeText(context, "Cannot open settings", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                      Toast.makeText(context, "An error occurred", Toast.LENGTH_SHORT).show()
                    }
                  }
                }
              }
            )
          )
        }
      }
      if (
        aboutText.lowercase().contains(searchLower) || aboutDesc.lowercase().contains(searchLower)
      ) {
        add(
          Material3SettingsItem(
            isHighlighted = (highlightKey == aboutText),
            icon = painterResource(R.drawable.info),
            title = { Text(aboutText) },
            description = { Text(aboutDesc) },
            onClick = { navController.navigate("settings/about") }
          )
        )
      }
    }

    val finalItemsList =
      if (searchQuery.isNotEmpty()) {
        val subSettings = getAllSearchableSettings()

        val matchedSubSettings =
          subSettings
            .filter {
              it.title.lowercase().contains(searchLower) ||
                (it.description?.lowercase()?.contains(searchLower) == true)
            }
            .map { setting ->
              Material3SettingsItem(
                icon = painterResource(R.drawable.search),
                title = { Text(setting.title) },
                description = {
                  if (setting.description != null) {
                    Text("${setting.category} • ${setting.description}")
                  } else {
                    Text(setting.category)
                  }
                },
                onClick = {
                  val encodedTitle = Uri.encode(setting.title)
                  val finalRoute =
                    if (setting.route.contains("?")) "${setting.route}&highlightKey=$encodedTitle"
                    else "${setting.route}?highlightKey=$encodedTitle"
                  navController.navigate(finalRoute)
                }
              )
            }

        itemsList + matchedSubSettings
      } else {
        itemsList
      }

    if (searchQuery.isNotEmpty() && finalItemsList.isEmpty()) {
      Spacer(modifier = Modifier.height(32.dp))
      Text(
        text = "No settings found for \"$searchQuery\"",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
      )
    } else if (searchQuery.isNotEmpty()) {
      Spacer(modifier = Modifier.height(16.dp))
      Material3SettingsGroup(scrollState = scrollState, items = finalItemsList)
    } else {
      Spacer(modifier = Modifier.height(16.dp))

      // Let's just create groups manually using itemsList which preserves order
      val accountGroup = itemsList.take(2) // Account, AI
      val listeningSummaryGroup = itemsList.drop(2).take(1) // Listening Summary
      val playerGroup = itemsList.drop(3).take(3) // Appearance, Player, Listen Together
      val dataGroup = itemsList.drop(6).take(4) // Content, Privacy, Storage, Backup
      val systemGroup = itemsList.drop(10) // Supported Links, About

      if (accountGroup.isNotEmpty()) {
        Material3SettingsGroup(scrollState = scrollState, items = accountGroup)
        Spacer(modifier = Modifier.height(16.dp))
      }
      if (listeningSummaryGroup.isNotEmpty()) {
        Material3SettingsGroup(scrollState = scrollState, items = listeningSummaryGroup)
        Spacer(modifier = Modifier.height(16.dp))
      }
      if (playerGroup.isNotEmpty()) {
        Material3SettingsGroup(scrollState = scrollState, items = playerGroup)
        Spacer(modifier = Modifier.height(16.dp))
      }
      if (dataGroup.isNotEmpty()) {
        Material3SettingsGroup(scrollState = scrollState, items = dataGroup)
        Spacer(modifier = Modifier.height(16.dp))
      }
      if (systemGroup.isNotEmpty()) {
        Material3SettingsGroup(scrollState = scrollState, items = systemGroup)
      }
    }

    Spacer(modifier = Modifier.height(50.dp))
    Spacer(
      Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
      )
    )
  }

  TopAppBar(
    title = {
      androidx.compose.animation.AnimatedVisibility(
        visible = scrollState.value > 100,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
      ) {
        Text(text = stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
      }
    },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    }
  )
}
